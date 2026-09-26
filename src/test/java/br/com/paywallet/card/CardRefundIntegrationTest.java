package br.com.paywallet.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.external.HmacSignatures;
import br.com.paywallet.user.UserDtos.UserResponse;

class CardRefundIntegrationTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

    @Autowired CardStatementService statements;

    @Test
    void debitRefundsAndWonDisputesReturnMoneyToTheWallet() throws Exception {
        var holder = newUserWithBalance("Refund Holder", "300.00");
        String cardId = issue(holder, "{\"type\":\"DEBIT\"}");
        String auth = purchase(cardId, "100.00");
        assertThat(balanceOf(holder)).isEqualByComparingTo("200.00");

        String refund = "{\"refundId\":\"ref-%s\",\"authorizationId\":\"%s\",\"amount\":40.00}"
                .formatted(UUID.randomUUID(), auth);
        webhook("refunds", refund).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("REFUND"));
        webhook("refunds", refund).andExpect(status().isOk());
        assertThat(balanceOf(holder)).isEqualByComparingTo("240.00");
        webhook("refunds", "{\"refundId\":\"ref-%s\",\"authorizationId\":\"%s\",\"amount\":60.01}"
                .formatted(UUID.randomUUID(), auth)).andExpect(status().isUnprocessableEntity());

        dispute(holder, cardId, auth)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.amount").value(60.00));
        dispute(holder, cardId, auth).andExpect(status().isConflict());
        verify(cardProcessor).openDispute(anyString(), eq(auth), eq(6_000L), eq("NOT_RECOGNIZED"));

        String outcome = "{\"authorizationId\":\"%s\",\"outcome\":\"WON\"}".formatted(auth);
        webhook("disputes", outcome).andExpect(jsonPath("$.status").value("WON"));
        webhook("disputes", outcome).andExpect(jsonPath("$.status").value("WON"));
        assertThat(balanceOf(holder)).isEqualByComparingTo("300.00");
        mvc.perform(get("/cards/{id}/disputes", cardId).with(as(holder)))
                .andExpect(jsonPath("$[0].status").value("WON"));
    }

    @Test
    void lostDisputesChangeNothing() throws Exception {
        var holder = newUserWithBalance("Losing Holder", "100.00");
        String cardId = issue(holder, "{\"type\":\"DEBIT\"}");
        String auth = purchase(cardId, "30.00");
        dispute(holder, cardId, auth).andExpect(status().isCreated());

        webhook("disputes", "{\"authorizationId\":\"%s\",\"outcome\":\"LOST\"}".formatted(auth))
                .andExpect(jsonPath("$.status").value("LOST"));
        assertThat(balanceOf(holder)).isEqualByComparingTo("70.00");
    }

    @Test
    void creditRefundsLowerTheNextStatement() throws Exception {
        var holder = newUserWithBalance("Credit Refund Holder", "5000.00");
        int closingDay = 12;
        String cardId = issue(holder, "{\"type\":\"CREDIT\",\"closingDay\":%d}".formatted(closingDay));
        String auth = purchase(cardId, "300.00");
        webhook("refunds", "{\"refundId\":\"ref-%s\",\"authorizationId\":\"%s\",\"amount\":100.00}"
                .formatted(UUID.randomUUID(), auth)).andExpect(status().isOk());
        mvc.perform(get("/cards/{id}", cardId).with(as(holder))).andExpect(jsonPath("$.availableLimit").value(1800.00));

        LocalDate closing = TODAY.getDayOfMonth() <= closingDay ? TODAY.withDayOfMonth(closingDay)
                : TODAY.plusMonths(1).withDayOfMonth(closingDay);
        statements.close(closing);
        mvc.perform(get("/cards/{id}/statements", cardId).with(as(holder)))
                .andExpect(jsonPath("$[0].total").value(200.00))
                .andExpect(jsonPath("$[0].charges[1].description").value("Refund: Shop"))
                .andExpect(jsonPath("$[0].charges[1].amount").value(-100.00));
    }

    @Test
    void onlyClearedPurchasesCanBeDisputed() throws Exception {
        var holder = newUserWithBalance("Early Disputer", "100.00");
        var other = newUserWithBalance("Other Holder", "100.00");
        String cardId = issue(holder, "{\"type\":\"DEBIT\"}");
        String pending = authorize(cardId, "10.00");

        dispute(holder, cardId, pending).andExpect(status().isConflict());
        String otherCard = issue(other, "{\"type\":\"DEBIT\"}");
        String cleared = purchase(cardId, "5.00");
        dispute(other, otherCard, cleared).andExpect(status().isNotFound());
    }

    private String issue(UserResponse holder, String body) throws Exception {
        return JsonPath.read(mvc.perform(post("/cards").with(as(holder)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String authorize(String cardId, String amount) throws Exception {
        String token = jdbc.queryForObject("SELECT processor_token FROM cards WHERE id = ?", String.class,
                UUID.fromString(cardId));
        String auth = "auth-" + UUID.randomUUID();
        webhook("authorizations",
                "{\"authorizationId\":\"%s\",\"cardToken\":\"%s\",\"amount\":%s,\"merchantName\":\"Shop\"}"
                        .formatted(auth, token, amount)).andExpect(jsonPath("$.responseCode").value("00"));
        return auth;
    }

    private String purchase(String cardId, String amount) throws Exception {
        String auth = authorize(cardId, amount);
        webhook("clearings", "{\"authorizationId\":\"%s\",\"amount\":%s}".formatted(auth, amount))
                .andExpect(jsonPath("$.status").value("CLEARED"));
        return auth;
    }

    private ResultActions dispute(UserResponse holder, String cardId, String auth) throws Exception {
        return mvc.perform(post("/cards/{id}/transactions/{auth}/disputes", cardId, auth).with(as(holder))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"NOT_RECOGNIZED\"}"));
    }

    private ResultActions webhook(String path, String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/cards/webhooks/" + path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Card-Timestamp", timestamp)
                .header("X-Card-Signature", HmacSignatures.sign(CARD_WEBHOOK_SECRET, timestamp + "." + body)));
    }
}
