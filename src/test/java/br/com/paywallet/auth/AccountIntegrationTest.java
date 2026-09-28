package br.com.paywallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.user.UserType;

class AccountIntegrationTest extends IntegrationTest {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    @Test
    void newAccountsMustConfirmTheirEmailBeforeLoggingIn() throws Exception {
        String email = "signup-" + UUID.randomUUID() + "@mail.com";
        signUp(email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerified").value(false));
        String code = lastCode(email, "Confirm your PayWallet email");

        login(email, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(startsWith("Email not verified")));
        verifyEmail(email, wrong(code)).andExpect(status().isUnprocessableEntity());
        verifyEmail(email, code).andExpect(status().isNoContent());
        verifyEmail(email, code).andExpect(status().isNoContent());
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aCodeStopsWorkingAfterTooManyWrongGuesses() throws Exception {
        String email = "guesser-" + UUID.randomUUID() + "@mail.com";
        signUp(email, PASSWORD).andExpect(status().isCreated());
        String code = lastCode(email, "Confirm your PayWallet email");

        for (int i = 0; i < 5; i++) {
            verifyEmail(email, wrong(code)).andExpect(status().isUnprocessableEntity());
        }
        verifyEmail(email, code)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Invalid or expired code"));
    }

    @Test
    void codesAreNotResentDuringTheCooldownAndUnknownEmailsLookTheSame() throws Exception {
        String email = "resend-" + UUID.randomUUID() + "@mail.com";
        signUp(email, PASSWORD).andExpect(status().isCreated());

        send("/auth/email/verification-code", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted());
        send("/auth/email/verification-code", "{\"email\":\"nobody-%s@mail.com\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isAccepted());
        send("/auth/password/forgot", "{\"email\":\"nobody-%s@mail.com\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isAccepted());

        verify(emailSender, times(1)).send(eq(email), anyString(), anyString());
    }

    @Test
    void passwordResetChangesThePasswordAndEndsEverySession() throws Exception {
        var user = newUser(UserType.COMMON, "Forgetful User");
        String refreshToken = JsonPath.read(login(user.email(), PASSWORD).andReturn().getResponse()
                .getContentAsString(), "$.refreshToken");

        send("/auth/password/forgot", "{\"email\":\"%s\"}".formatted(user.email().toUpperCase()))
                .andExpect(status().isAccepted());
        String code = lastCode(user.email(), "Reset your PayWallet password");
        String newPassword = "a-brand-new-passphrase";

        reset(user.email(), code, "too-short").andExpect(status().isUnprocessableEntity());
        reset(user.email(), wrong(code), newPassword).andExpect(status().isUnprocessableEntity());
        reset(user.email(), code, newPassword).andExpect(status().isNoContent());
        reset(user.email(), code, "yet-another-passphrase").andExpect(status().isUnprocessableEntity());

        login(user.email(), PASSWORD).andExpect(status().isUnauthorized());
        login(user.email(), newPassword).andExpect(status().isOk());
        send("/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(refreshToken)).andExpect(status().isUnauthorized());
        verify(emailSender).send(eq(user.email()), eq("Your PayWallet password was changed"), anyString());
    }

    @Test
    void loggedInUsersChangeTheirPasswordWithTheCurrentOne() throws Exception {
        var user = newUser(UserType.COMMON, "Careful User");
        String body = "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}";

        mvc.perform(post("/auth/password/change").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("not-my-password-at-all", "a-brand-new-passphrase")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Current password is incorrect"));
        mvc.perform(post("/auth/password/change").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(PASSWORD, "a-brand-new-passphrase")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/auth/password/change").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(PASSWORD, "a-brand-new-passphrase")))
                .andExpect(status().isUnauthorized());

        login(user.email(), "a-brand-new-passphrase").andExpect(status().isOk());
    }

    @Test
    void weakPasswordsAreRefusedAtSignUp() throws Exception {
        String email = "weakling-" + UUID.randomUUID() + "@mail.com";
        signUp(email, "short-pass").andExpect(status().isBadRequest());
        signUp(email, "my-" + email.substring(0, email.indexOf('@')) + "-password")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Password must not contain your email or document"));
        verify(emailSender, never()).send(eq(email), anyString(), anyString());
    }

    private ResultActions signUp(String email, String password) throws Exception {
        String document = "%011d".formatted(ThreadLocalRandom.current().nextLong(1_000_000_000L, 99_999_999_999L));
        return send("/users", """
                {"fullName": "New Customer", "document": "%s", "email": "%s", "password": "%s", "type": "COMMON",
                 "acceptedTerms": true}
                """.formatted(document, email, password));
    }

    private ResultActions login(String email, String password) throws Exception {
        return send("/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private ResultActions verifyEmail(String email, String code) throws Exception {
        return send("/auth/email/verify", "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code));
    }

    private ResultActions reset(String email, String code, String password) throws Exception {
        return send("/auth/password/reset", "{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"%s\"}"
                .formatted(email, code, password));
    }

    private ResultActions send(String path, String json) throws Exception {
        return mvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String lastCode(String email, String subject) {
        var text = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).send(eq(email), eq(subject), text.capture());
        List<String> texts = text.getAllValues();
        var matcher = CODE.matcher(texts.getLast());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String wrong(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }
}
