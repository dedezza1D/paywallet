package br.com.paywallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

class AuthIntegrationTest extends IntegrationTest {

    @Autowired JwtEncoder jwtEncoder;
    @Autowired SecurityProperties props;

    @Test
    void loginIssuesTokensThatAccessOwnResources() throws Exception {
        var mary = newUser(UserType.COMMON, "Login Mary");

        var body = login(mary.email(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(body, "$.accessToken");
        String refreshToken = JsonPath.read(body, "$.refreshToken");
        assertThat(refreshToken).hasSizeGreaterThanOrEqualTo(43);

        mvc.perform(get("/users/{id}/balance", mary.id()).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        var user = newUser(UserType.COMMON, "Indistinguishable");

        var wrongPassword = login(user.email(), "wrong-password-123").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        var unknownEmail = login("nobody-" + newKey() + "@mail.com", "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(wrongPassword, "$.detail"))
                .isEqualTo(JsonPath.<String>read(unknownEmail, "$.detail"));
    }

    @Test
    void repeatedFailuresLockTheAccountTemporarily() throws Exception {
        var user = newUser(UserType.COMMON, "Forca Bruta");
        for (int i = 0; i < props.loginMaxAttempts(); i++) {
            login(user.email(), "guess-" + i).andExpect(status().isUnauthorized());
        }

        // Even the correct password is refused until the lock expires.
        login(user.email(), PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        var user = newUser(UserType.COMMON, "Case");
        login(user.email().toUpperCase(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeSession() throws Exception {
        var user = newUser(UserType.COMMON, "Rotation");
        String first = JsonPath.read(login(user.email(), PASSWORD).andReturn().getResponse().getContentAsString(),
                "$.refreshToken");

        String second = JsonPath.read(refresh(first).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");
        assertThat(second).isNotEqualTo(first);

        // An already-rotated token presented again signals theft.
        refresh(first).andExpect(status().isUnauthorized());
        // The whole family is revoked, including the newest token.
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        var user = newUser(UserType.COMMON, "Logout");
        String refresh = JsonPath.read(login(user.email(), PASSWORD).andReturn().getResponse().getContentAsString(),
                "$.refreshToken");

        mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"%s\"}".formatted(refresh)))
                .andExpect(status().isNoContent());

        refresh(refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void garbageRefreshTokenIsRejected() throws Exception {
        refresh("does-not-exist").andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithoutTokenGets401ProblemJson() throws Exception {
        var user = newUser(UserType.COMMON, "Anonymous");
        mvc.perform(get("/users/{id}/balance", user.id()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        var user = newUser(UserType.COMMON, "Expired");
        var past = Instant.now().minusSeconds(3600);
        String token = sign(jwtEncoder, claims(user).issuedAt(past.minusSeconds(900)).expiresAt(past).build());

        mvc.perform(get("/users/{id}/balance", user.id()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        var user = newUser(UserType.COMMON, "Forged");
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("attacker").generate();
        var attackerEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(attackerKey)));
        String token = sign(attackerEncoder, claims(user).build());

        mvc.perform(get("/users/{id}/balance", user.id()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForAnotherAudienceIsRejected() throws Exception {
        var user = newUser(UserType.COMMON, "Audience");
        String token = sign(jwtEncoder, claims(user).audience(List.of("another-system")).build());

        mvc.perform(get("/users/{id}/balance", user.id()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotReachAnotherUsersResources() throws Exception {
        var alice = newUser(UserType.COMMON, "Alice");
        var mallory = newUser(UserType.COMMON, "Mallory");

        for (String path : List.of("/users/{id}", "/users/{id}/balance", "/users/{id}/statement",
                "/users/{id}/limits", "/users/{id}/feed", "/users/{id}/kyc-documents")) {
            mvc.perform(get(path, alice.id()).with(as(mallory)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    /** Even with someone else's "payer" in the body, the payer is always the token owner. */
    @Test
    void payerAlwaysComesFromTheToken() throws Exception {
        var victim = newUserWithBalance("Victim", "100.00");
        var attacker = newUserWithBalance("Attacker", "10.00");

        mvc.perform(post("/transfer").with(as(attacker))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5, \"payer\": %d, \"payee\": %d}".formatted(victim.id(), attacker.id())))
                .andExpect(status().isUnprocessableEntity()); // "cannot transfer to yourself"

        assertThat(balanceOf(victim)).isEqualByComparingTo("100.00");
    }

    @Test
    void listingAllUsersAndLedgerAuditRequireAdmin() throws Exception {
        var user = newUser(UserType.COMMON, "Regular");
        mvc.perform(get("/users").with(as(user))).andExpect(status().isForbidden());
        mvc.perform(get("/ledger/reconciliation").with(as(user))).andExpect(status().isForbidden());

        makeAdmin(user);
        mvc.perform(get("/users").with(as(user))).andExpect(status().isOk());
        mvc.perform(get("/ledger/reconciliation").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    /** Creating money is an admin operation; self-deposit is disabled outside development. */
    @Test
    void depositRequiresAdmin() throws Exception {
        var user = newUser(UserType.COMMON, "Deposit");
        var admin = newUser(UserType.COMMON, "Operator");
        makeAdmin(admin);

        deposit(user, user).andExpect(status().isForbidden());
        deposit(admin, user).andExpect(status().isOk());
        assertThat(balanceOf(user)).isEqualByComparingTo("10.00");
    }

    @Test
    void signupRequiresAcceptingTheTerms() throws Exception {
        for (String accepted : new String[] {"false", "null"}) {
            mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("""
                            {"fullName": "Undecided", "document": "%011d", "email": "undecided-%s@mail.com",
                             "password": "password1234", "type": "COMMON", "acceptedTerms": %s}
                            """.formatted(System.nanoTime() % 100_000_000_000L, newKey(), accepted)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.acceptedTerms").exists());
        }
    }

    @Test
    void signupIsPublicAndJwksExposesOnlyThePublicKey() throws Exception {
        String email = "newcomer-%s@mail.com".formatted(newKey()).toLowerCase();
        mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName": "Newcomer", "document": "%011d", "email": "%s",
                         "password": "password1234", "type": "COMMON", "acceptedTerms": true}
                        """.formatted(System.nanoTime() % 100_000_000_000L, email)))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForMap("SELECT terms_version, terms_accepted_at FROM users WHERE email = ?", email))
                .doesNotContainValue(null);

        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").exists())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\": \"%s\"}".formatted(refreshToken)));
    }

    private ResultActions deposit(UserResponse caller, UserResponse target) throws Exception {
        return mvc.perform(post("/users/{id}/deposit", target.id()).with(as(caller))
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": %s}".formatted(new BigDecimal("10.00"))));
    }

    private JwtClaimsSet.Builder claims(UserResponse user) {
        var now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(props.jwt().issuer())
                .audience(List.of(props.jwt().audience()))
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim(TokenService.ROLES_CLAIM, List.of("CUSTOMER"));
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
