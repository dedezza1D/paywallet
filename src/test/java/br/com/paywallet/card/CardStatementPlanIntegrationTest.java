package br.com.paywallet.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.external.HmacSignatures;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.user.UserDtos.UserResponse;

/** A band B customer has a R$ 2,000 limit; installment plans cost 8.99% a month. */
class CardStatementPlanIntegrationTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    private static final int CLOSING_DAY = 18;

    @Autowired CardStatementService statements;

    @Test
    void anOpenStatementCanBeSplitIntoInstallments() throws Exception {
        var holder = newUserWithBalance("Plan Holder", "5000.00");
        String cardId = JsonPath.read(mvc.perform(post("/cards").with(as(holder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CREDIT\",\"closingDay\":%d}".formatted(CLOSING_DAY)))
                .andReturn().getResponse().getContentAsString(), "$.id");
        purchase(cardId, "1000.00");
        LocalDate closing = TODAY.getDayOfMonth() <= CLOSING_DAY ? TODAY.withDayOfMonth(CLOSING_DAY)
                : TODAY.plusMonths(1).withDayOfMonth(CLOSING_DAY);
        statements.close(closing);
        String statementId = JsonPath.read(mvc.perform(get("/cards/{id}/statements", cardId).with(as(holder)))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        long income = accountBalance(AccountType.INTEREST_INCOME_ACCOUNT_ID);

        // Price table: 1000 * 0.0899 / (1 - 1.0899^-3) = 394.98
        mvc.perform(get("/cards/{id}/statements/{sid}/installment-options", cardId, statementId).with(as(holder)))
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[1].installments").value(3))
                .andExpect(jsonPath("$[1].installmentAmount").value(394.98))
                .andExpect(jsonPath("$[1].total").value(1184.94))
                .andExpect(jsonPath("$[1].monthlyRate").value(8.99));

        finance(holder, cardId, statementId, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINANCED"));
        finance(holder, cardId, statementId, 3).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/cards/{id}/statements/{sid}/payment", cardId, statementId).with(as(holder))
                .header("Idempotency-Key", newKey())).andExpect(status().isUnprocessableEntity());

        List<Long> installments = jdbc.queryForList("""
                SELECT amount FROM card_charges WHERE card_id = ? AND description = 'Statement installment'
                 ORDER BY installment
                """, Long.class, UUID.fromString(cardId));
        assertThat(installments).containsExactly(39_498L, 39_498L, 39_498L);
        assertThat(accountBalance(AccountType.INTEREST_INCOME_ACCOUNT_ID) - income).isEqualTo(18_494);
        mvc.perform(get("/cards/{id}", cardId).with(as(holder)))
                .andExpect(jsonPath("$.availableLimit").value(815.06));
        assertThat(balanceOf(holder)).isEqualByComparingTo("5000.00");
    }

    private void purchase(String cardId, String amount) throws Exception {
        String token = jdbc.queryForObject("SELECT processor_token FROM cards WHERE id = ?", String.class,
                UUID.fromString(cardId));
        String auth = "auth-" + UUID.randomUUID();
        webhook("authorizations",
                "{\"authorizationId\":\"%s\",\"cardToken\":\"%s\",\"amount\":%s,\"merchantName\":\"TV Store\"}"
                        .formatted(auth, token, amount)).andExpect(jsonPath("$.responseCode").value("00"));
        webhook("clearings", "{\"authorizationId\":\"%s\",\"amount\":%s}".formatted(auth, amount))
                .andExpect(jsonPath("$.status").value("CLEARED"));
    }

    private ResultActions finance(UserResponse holder, String cardId, String statementId, int installments)
            throws Exception {
        return mvc.perform(post("/cards/{id}/statements/{sid}/installment-plan", cardId, statementId).with(as(holder))
                .contentType(MediaType.APPLICATION_JSON).content("{\"installments\": %d}".formatted(installments)));
    }

    private ResultActions webhook(String path, String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/cards/webhooks/" + path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Card-Timestamp", timestamp)
                .header("X-Card-Signature", HmacSignatures.sign(CARD_WEBHOOK_SECRET, timestamp + "." + body)));
    }

    private long accountBalance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }
}
