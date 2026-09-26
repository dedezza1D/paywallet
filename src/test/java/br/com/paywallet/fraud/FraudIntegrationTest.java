package br.com.paywallet.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.external.HmacSignatures;
import br.com.paywallet.pix.PixGateway.ExternalAccount;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

/** Every test user is a new account, so payments of R$ 500 or more also score NEW_ACCOUNT. */
class FraudIntegrationTest extends IntegrationTest {

    @Test
    void unusualPaymentsAreReviewedAndBurstsAreDeclined() throws Exception {
        var payer = newUserWithBalance("Risky Payer", "5000.00");
        var usual = newUser(UserType.COMMON, "Usual Payee");
        var first = newUser(UserType.COMMON, "First Stranger");
        var second = newUser(UserType.COMMON, "Second Stranger");

        for (int i = 0; i < 4; i++) {
            transfer(payer, usual, "10.00").andExpect(status().isCreated());
        }
        transfer(payer, first, "1000.00").andExpect(status().isCreated());
        transfer(payer, second, "1500.00")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(
                        "Operation blocked by risk analysis. Contact support if you did not expect this."));
        assertThat(balanceOf(payer)).isEqualByComparingTo("3960.00");

        var admin = admin();
        mvc.perform(get("/admin/fraud/alerts").param("userId", payer.id().toString()).with(as(admin)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].decision").value("DECLINE"))
                .andExpect(jsonPath("$[0].score").value(100))
                .andExpect(jsonPath("$[0].rules", contains("VELOCITY", "AMOUNT_ANOMALY", "NEW_COUNTERPARTY",
                        "NEW_ACCOUNT")))
                .andExpect(jsonPath("$[0].counterparty").value("user:" + second.id()))
                .andExpect(jsonPath("$[1].decision").value("REVIEW"))
                .andExpect(jsonPath("$[1].score").value(75))
                .andExpect(jsonPath("$[1].status").value("OPEN"));
    }

    @Test
    void confirmedFraudFreezesOutflowsButNotInflows() throws Exception {
        var suspect = newUserWithBalance("Suspect", "3000.00");
        var mule = newUser(UserType.COMMON, "Mule");
        var friend = newUserWithBalance("Friend", "100.00");
        for (int i = 0; i < 3; i++) {
            transfer(suspect, friend, "10.00").andExpect(status().isCreated());
        }
        transfer(suspect, mule, "2000.00").andExpect(status().isCreated());
        var admin = admin();
        String alertId = JsonPath.read(mvc.perform(get("/admin/fraud/alerts").param("userId", suspect.id().toString())
                        .param("status", "OPEN").with(as(admin)))
                .andExpect(jsonPath("$[0].decision").value("REVIEW"))
                .andExpect(jsonPath("$[0].rules", contains("AMOUNT_ANOMALY", "NEW_COUNTERPARTY", "NEW_ACCOUNT")))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        mvc.perform(post("/admin/fraud/alerts/" + alertId + "/resolution").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"note\":\"Account takeover\",\"watchlistCounterparty\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.resolvedBy").value(admin.id()));
        mvc.perform(post("/admin/fraud/alerts/" + alertId + "/resolution").with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isUnprocessableEntity());

        transfer(suspect, friend, "1.00").andExpect(status().isForbidden());
        transfer(friend, suspect, "5.00").andExpect(status().isCreated());
        transfer(friend, mule, "5.00").andExpect(status().isForbidden());
        mvc.perform(get("/admin/fraud/blocked-users/" + suspect.id()).with(as(admin)))
                .andExpect(jsonPath("$.reason").value("Account takeover"));

        mvc.perform(delete("/admin/fraud/blocked-users/" + suspect.id()).with(as(admin)))
                .andExpect(status().isNoContent());
        transfer(suspect, friend, "1.00").andExpect(status().isCreated());
    }

    @Test
    void watchlistedPixKeysAreRefused() throws Exception {
        var payer = newUserWithBalance("Pix Payer", "100.00");
        String key = "scam-" + UUID.randomUUID() + "@otherbank.com";
        doReturn(Optional.of(new ExternalAccount("Scammer", "99988877766", "60701190", "Other Bank")))
                .when(pixGateway).lookup(key);
        var admin = admin();

        mvc.perform(post("/admin/fraud/watchlist").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"pix:%s\",\"reason\":\"Reported by customers\"}".formatted(key)))
                .andExpect(status().isCreated());
        mvc.perform(post("/admin/fraud/watchlist").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"%s\",\"reason\":\"No prefix\"}".formatted(key)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/pix/payments").with(as(payer)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"%s\",\"value\":10.00}".formatted(key)))
                .andExpect(status().isForbidden());
        assertThat(balanceOf(payer)).isEqualByComparingTo("100.00");
        mvc.perform(get("/admin/fraud/alerts").param("userId", payer.id().toString()).with(as(admin)))
                .andExpect(jsonPath("$[0].channel").value("PIX"))
                .andExpect(jsonPath("$[0].rules", contains("WATCHLISTED_COUNTERPARTY")));
        mvc.perform(get("/admin/fraud/watchlist").with(as(admin)))
                .andExpect(jsonPath("$[*].value", hasItem("pix:" + key)));
    }

    @Test
    void repeatedCardDeclinesLookLikeCardTesting() throws Exception {
        var holder = newUserWithBalance("Tested Holder", "20.00");
        String cardId = JsonPath.read(mvc.perform(post("/cards").with(as(holder))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DEBIT\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String token = jdbc.queryForObject("SELECT processor_token FROM cards WHERE id = ?", String.class,
                UUID.fromString(cardId));

        for (int i = 0; i < 5; i++) {
            authorize(token, "99.00").andExpect(jsonPath("$.responseCode").value("51"));
        }
        authorize(token, "5.00")
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.responseCode").value("59"))
                .andExpect(jsonPath("$.reason").value("Suspected fraud"));
        assertThat(balanceOf(holder)).isEqualByComparingTo("20.00");
    }

    @Test
    void fraudToolsAreAdminOnly() throws Exception {
        var user = newUser(UserType.COMMON, "Nosy");
        mvc.perform(get("/admin/fraud/alerts").with(as(user))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/fraud/blocked-users/" + user.id()).with(as(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    private UserResponse admin() {
        var admin = newUser(UserType.COMMON, "Fraud Analyst");
        makeAdmin(admin);
        return admin;
    }

    private ResultActions transfer(UserResponse payer, UserResponse payee, String value) throws Exception {
        return mvc.perform(post("/transfer").with(as(payer)).header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":%s,\"payee\":%d}".formatted(value, payee.id())));
    }

    private ResultActions authorize(String cardToken, String amount) throws Exception {
        String body = "{\"authorizationId\":\"auth-%s\",\"cardToken\":\"%s\",\"amount\":%s,\"merchantName\":\"Shop\"}"
                .formatted(UUID.randomUUID(), cardToken, amount);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/cards/webhooks/authorizations").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Card-Timestamp", timestamp)
                .header("X-Card-Signature", HmacSignatures.sign(CARD_WEBHOOK_SECRET, timestamp + "." + body)));
    }
}
