package br.com.paywallet.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.credit.CreditBureau;
import br.com.paywallet.external.HmacSignatures;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

/**
 * A new account with a R$ 5,000 deposit and a bureau score of 800 falls in risk band B, so its credit card
 * limit is R$ 2,000.
 */
class CardIntegrationTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

    @Autowired CardStatementService statementService;

    @Test
    void issuesVirtualCardsWithoutStoringCardNumbers() throws Exception {
        var holder = newUserWithBalance("Card Holder", "5000.00");

        mvc.perform(post("/cards").with(as(holder)).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DEBIT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.last4").value("4242"))
                .andExpect(jsonPath("$.creditLimit").doesNotExist())
                .andExpect(content().string(not(containsString("tok_"))));
        mvc.perform(post("/cards").with(as(holder)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CREDIT\",\"closingDay\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditLimit").value(2000.00))
                .andExpect(jsonPath("$.availableLimit").value(2000.00))
                .andExpect(jsonPath("$.closingDay").value(10));

        mvc.perform(post("/cards").with(as(holder)).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DEBIT\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/cards").with(as(holder))).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void creditCardsRequireAnApprovedIndividual() throws Exception {
        var merchant = newUser(UserType.MERCHANT, "Card Shop");
        var restricted = newUserWithBalance("Card Restricted", "5000.00");
        doReturn(new CreditBureau.BureauReport(900, true))
                .when(creditBureau).report(restricted.document());

        mvc.perform(post("/cards").with(as(merchant)).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CREDIT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Credit cards are available to individuals only"));
        mvc.perform(post("/cards").with(as(restricted)).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CREDIT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Credit not approved: Overdue debts registered at the credit bureau"));
    }

    @Test
    void debitPurchasesHoldTheBalanceUntilClearingOrReversal() throws Exception {
        var holder = newUserWithBalance("Debit Holder", "500.00");
        String token = issue(holder, "DEBIT", null);
        long settlementBefore = accountBalance(AccountType.CARD_SETTLEMENT_ACCOUNT_ID);

        String purchase = newAuthId();
        authorize(purchase, token, "120.00", 1).andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.responseCode").value("00"));
        assertThat(balanceOf(holder)).isEqualByComparingTo("380.00");
        authorize(purchase, token, "120.00", 1).andExpect(jsonPath("$.approved").value(true));
        assertThat(balanceOf(holder)).isEqualByComparingTo("380.00");

        webhook("clearings", "{\"authorizationId\":\"%s\",\"amount\":100.00}".formatted(purchase))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEARED"))
                .andExpect(jsonPath("$.clearedAmount").value(100.00));
        assertThat(balanceOf(holder)).isEqualByComparingTo("400.00");
        assertThat(accountBalance(AccountType.CARD_SETTLEMENT_ACCOUNT_ID) - settlementBefore).isEqualTo(10_000);
        webhook("clearings", "{\"authorizationId\":\"%s\",\"amount\":100.00}".formatted(purchase))
                .andExpect(status().isOk());
        webhook("clearings", "{\"authorizationId\":\"%s\",\"amount\":90.00}".formatted(purchase))
                .andExpect(status().isConflict());

        String cancelled = newAuthId();
        authorize(cancelled, token, "50.00", 1).andExpect(jsonPath("$.approved").value(true));
        webhook("reversals", "{\"authorizationId\":\"%s\"}".formatted(cancelled))
                .andExpect(jsonPath("$.status").value("REVERSED"));
        assertThat(balanceOf(holder)).isEqualByComparingTo("400.00");

        authorize(newAuthId(), token, "400.01", 1)
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.responseCode").value("51"));
        authorize(newAuthId(), token, "30.00", 3)
                .andExpect(jsonPath("$.responseCode").value("57"));
        authorize(newAuthId(), "tok_unknown", "30.00", 1)
                .andExpect(jsonPath("$.responseCode").value("14"));
        assertThat(balanceOf(holder)).isEqualByComparingTo("400.00");
    }

    @Test
    void blockedAndCancelledCardsAreDeclined() throws Exception {
        var holder = newUserWithBalance("Blocking Holder", "500.00");
        var other = newUserWithBalance("Curious", "10.00");
        String token = issue(holder, "DEBIT", null);
        String cardId = cardId(holder, "DEBIT");

        mvc.perform(get("/cards/" + cardId).with(as(other))).andExpect(status().isNotFound());
        mvc.perform(post("/cards/" + cardId + "/block").with(as(holder))).andExpect(jsonPath("$.status").value("BLOCKED"));
        authorize(newAuthId(), token, "10.00", 1).andExpect(jsonPath("$.responseCode").value("57"));
        mvc.perform(post("/cards/" + cardId + "/unblock").with(as(holder))).andExpect(jsonPath("$.status").value("ACTIVE"));
        authorize(newAuthId(), token, "10.00", 1).andExpect(jsonPath("$.responseCode").value("00"));
        mvc.perform(post("/cards/" + cardId + "/cancel").with(as(holder))).andExpect(jsonPath("$.status").value("CANCELLED"));
        authorize(newAuthId(), token, "10.00", 1).andExpect(jsonPath("$.responseCode").value("14"));
        mvc.perform(post("/cards/" + cardId + "/unblock").with(as(holder))).andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/cards/" + cardId + "/transactions").with(as(holder)))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].declineReason").value("Card cancelled"));
        mvc.perform(post("/cards").with(as(holder)).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DEBIT\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void webhooksRequireAValidSignature() throws Exception {
        String body = "{\"authorizationId\":\"%s\",\"cardToken\":\"tok_x\",\"amount\":1.00,\"merchantName\":\"X\"}"
                .formatted(newAuthId());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        mvc.perform(post("/cards/webhooks/authorizations").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Card-Timestamp", timestamp)
                        .header("X-Card-Signature", HmacSignatures.sign("wrong-secret", timestamp + "." + body)))
                .andExpect(status().isUnauthorized());
        webhook("authorizations", "{\"authorizationId\":\"x\"}").andExpect(status().isBadRequest());
    }

    @Test
    void creditPurchasesAreBilledInInstallmentsAndUnpaidBalancesRevolve() throws Exception {
        var holder = newUserWithBalance("Credit Holder", "5000.00");
        String token = issue(holder, "CREDIT", 5);
        String cardId = cardId(holder, "CREDIT");
        long interestBefore = accountBalance(AccountType.INTEREST_INCOME_ACCOUNT_ID);

        String purchase = newAuthId();
        authorize(purchase, token, "1200.00", 3).andExpect(jsonPath("$.approved").value(true));
        mvc.perform(get("/cards/" + cardId).with(as(holder))).andExpect(jsonPath("$.availableLimit").value(800.00));
        authorize(newAuthId(), token, "800.01", 1)
                .andExpect(jsonPath("$.responseCode").value("51"))
                .andExpect(jsonPath("$.reason").value("Insufficient credit limit"));
        assertThat(balanceOf(holder)).isEqualByComparingTo("5000.00");

        webhook("clearings", "{\"authorizationId\":\"%s\",\"amount\":1200.00}".formatted(purchase))
                .andExpect(jsonPath("$.status").value("CLEARED"));
        mvc.perform(get("/cards/" + cardId).with(as(holder))).andExpect(jsonPath("$.availableLimit").value(800.00));

        LocalDate firstClosing = TODAY.getDayOfMonth() <= 5 ? TODAY.withDayOfMonth(5) : TODAY.plusMonths(1).withDayOfMonth(5);
        var admin = newUser(UserType.COMMON, "Card Admin");
        makeAdmin(admin);
        mvc.perform(post("/admin/cards/statements/close").param("date", firstClosing.toString()).with(as(admin)))
                .andExpect(status().isOk());
        String statements = mvc.perform(get("/cards/" + cardId + "/statements").with(as(holder)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].total").value(400.00))
                .andExpect(jsonPath("$[0].minimumPayment").value(60.00))
                .andExpect(jsonPath("$[0].dueDate").value(firstClosing.plusDays(10).toString()))
                .andExpect(jsonPath("$[0].charges[0].description").value("Merchant"))
                .andExpect(jsonPath("$[0].charges[0].installment").value(1))
                .andExpect(jsonPath("$[0].charges[0].installments").value(3))
                .andReturn().getResponse().getContentAsString();
        String first = JsonPath.read(statements, "$[0].id");
        assertThat(statementService.close(firstClosing).closed()).isZero();

        String key = newKey();
        pay(holder, cardId, first, "{\"value\":100.00}", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(300.00))
                .andExpect(jsonPath("$.status").value("OPEN"));
        pay(holder, cardId, first, "{\"value\":100.00}", key)
                .andExpect(header().string("Idempotent-Replayed", "true"));
        pay(holder, cardId, first, "{\"value\":300.01}", newKey()).andExpect(status().isUnprocessableEntity());
        assertThat(balanceOf(holder)).isEqualByComparingTo("4900.00");

        var second = statementService.close(firstClosing.plusMonths(1));
        assertThat(second.carried()).isGreaterThanOrEqualTo(1);
        String after = mvc.perform(get("/cards/" + cardId + "/statements").with(as(holder)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].total").value(729.97))
                .andExpect(jsonPath("$[1].status").value("CARRIED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(accountBalance(AccountType.INTEREST_INCOME_ACCOUNT_ID) - interestBefore).isEqualTo(2_997);

        pay(holder, cardId, JsonPath.read(after, "$[0].id"), "", newKey())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paid").value(729.97));
        assertThat(balanceOf(holder)).isEqualByComparingTo("4170.03");
        mvc.perform(get("/cards/" + cardId).with(as(holder))).andExpect(jsonPath("$.availableLimit").value(1600.00));
        mvc.perform(post("/cards/" + cardId + "/cancel").with(as(holder))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void concurrentPurchasesCannotExceedTheCreditLimit() throws Exception {
        var holder = newUserWithBalance("Concurrent Holder", "5000.00");
        String token = issue(holder, "CREDIT", 20);

        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> codes = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                codes.add(executor.submit(() -> JsonPath.read(authorize(newAuthId(), token, "600.00", 1)
                        .andReturn().getResponse().getContentAsString(), "$.responseCode")));
            }
            List<String> results = new ArrayList<>();
            for (var code : codes) {
                results.add(code.get());
            }
            assertThat(results).filteredOn("00"::equals).hasSize(3);
            assertThat(results).filteredOn("51"::equals).hasSize(5);
        } finally {
            executor.shutdown();
        }
    }

    private String issue(UserResponse user, String type, Integer closingDay) throws Exception {
        String body = closingDay == null ? "{\"type\":\"%s\"}".formatted(type)
                : "{\"type\":\"%s\",\"closingDay\":%d}".formatted(type, closingDay);
        String id = JsonPath.read(mvc.perform(post("/cards").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        return jdbc.queryForObject("SELECT processor_token FROM cards WHERE id = ?", String.class, UUID.fromString(id));
    }

    private String cardId(UserResponse user, String type) {
        return jdbc.queryForObject("SELECT id FROM cards WHERE user_id = ? AND type = ? AND status <> 'CANCELLED'",
                UUID.class, user.id(), type).toString();
    }

    private ResultActions authorize(String authorizationId, String cardToken, String amount, int installments)
            throws Exception {
        return webhook("authorizations", """
                {"authorizationId":"%s","cardToken":"%s","amount":%s,"merchantName":"Merchant","mcc":"5411",
                 "installments":%d}
                """.formatted(authorizationId, cardToken, amount, installments))
                .andExpect(status().isOk());
    }

    private ResultActions webhook(String path, String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return mvc.perform(post("/cards/webhooks/" + path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Card-Timestamp", timestamp)
                .header("X-Card-Signature", HmacSignatures.sign(CARD_WEBHOOK_SECRET, timestamp + "." + body)));
    }

    private ResultActions pay(UserResponse user, String cardId, String statementId, String body, String key)
            throws Exception {
        var request = post("/cards/%s/statements/%s/payment".formatted(cardId, statementId)).with(as(user))
                .header("Idempotency-Key", key);
        if (!body.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private long accountBalance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private static String newAuthId() {
        return "auth-" + UUID.randomUUID();
    }
}
