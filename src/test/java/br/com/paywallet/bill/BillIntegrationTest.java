package br.com.paywallet.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.bill.BillGateway.BillQuote;
import br.com.paywallet.bill.BillGateway.PaymentResult;
import br.com.paywallet.exception.ExternalServiceException;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

class BillIntegrationTest extends IntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired LedgerService ledger;

    @Test
    void lookupShowsBeneficiaryAmountDueAndDeadline() throws Exception {
        var user = newUser(UserType.COMMON, "Bill Viewer");
        String line = bankLine(15_990, TODAY.plusDays(10));
        stubQuote(line, fixedQuote(15_990, 15_990, TODAY.plusDays(10), TODAY.plusDays(40)));

        mvc.perform(post("/bills/lookup").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(line)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("BANK"))
                .andExpect(jsonPath("$.bankCode").value("341"))
                .andExpect(jsonPath("$.beneficiaryName").value("City Power Company"))
                .andExpect(jsonPath("$.beneficiaryDocument").value("12.345.678/0001-99"))
                .andExpect(jsonPath("$.valueDue").value(159.90))
                .andExpect(jsonPath("$.dueDate").value(TODAY.plusDays(10).toString()))
                .andExpect(jsonPath("$.payable").value(true));
    }

    @Test
    void rejectsInvalidAndUnknownCodes() throws Exception {
        var user = newUser(UserType.COMMON, "Bill Typo");
        String line = bankLine(1_000, TODAY);
        String typo = line.substring(0, 20) + (char) ('0' + (line.charAt(20) - '0' + 1) % 10) + line.substring(21);

        lookup(user, typo).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Invalid boleto code"));
        lookup(user, line).andExpect(status().isNotFound());
    }

    @Test
    void paysABankBoletoAndConfirmsWithTheBankAuthentication() throws Exception {
        var payer = newUserWithBalance("Bill Payer", "200.00");
        String line = bankLine(15_990, TODAY.plusDays(5));
        stubQuote(line, fixedQuote(15_990, 15_990, TODAY.plusDays(5), TODAY.plusDays(35)));

        String body = pay(payer, newKey(), line, null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.value").value(159.90))
                .andReturn().getResponse().getContentAsString();
        assertThat(balanceOf(payer)).isEqualByComparingTo("40.10");

        String id = JsonPath.read(body, "$.id");
        awaitStatus(payer, id, "CONFIRMED");
        mvc.perform(get("/bills/payments/{id}", id).with(as(payer)))
                .andExpect(jsonPath("$.authenticationCode").value("AUTH-TEST"))
                .andExpect(jsonPath("$.settledAt").exists());
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void paysTheAmountDueIncludingInterest() throws Exception {
        var payer = newUserWithBalance("Late Bill Payer", "200.00");
        String line = bankLine(10_000, TODAY.minusDays(3));
        stubQuote(line, fixedQuote(10_000, 10_236, TODAY.minusDays(3), TODAY.plusDays(27)));

        pay(payer, newKey(), line, null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.value").value(102.36));
        assertThat(balanceOf(payer)).isEqualByComparingTo("97.64");
    }

    @Test
    void aBillIsPaidOnlyOnce() throws Exception {
        var first = newUserWithBalance("First Bill Payer", "100.00");
        var second = newUserWithBalance("Second Bill Payer", "100.00");
        String line = bankLine(5_000, TODAY.plusDays(5));
        stubQuote(line, fixedQuote(5_000, 5_000, TODAY.plusDays(5), TODAY.plusDays(35)));
        String key = newKey();

        pay(first, key, line, null).andExpect(status().isAccepted());
        pay(first, key, line, null).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        pay(second, newKey(), line, null).andExpect(status().isConflict());

        assertThat(balanceOf(first)).isEqualByComparingTo("50.00");
        assertThat(balanceOf(second)).isEqualByComparingTo("100.00");
    }

    @Test
    void refusesBillsPastTheirDeadline() throws Exception {
        var payer = newUserWithBalance("Too Late", "100.00");
        String line = bankLine(5_000, TODAY.minusDays(90));
        stubQuote(line, fixedQuote(5_000, 5_300, TODAY.minusDays(90), TODAY.minusDays(1)));

        lookup(payer, line).andExpect(jsonPath("$.payable").value(false))
                .andExpect(jsonPath("$.notPayableReason").value("Payment deadline has passed"));
        pay(payer, newKey(), line, null).andExpect(status().isUnprocessableEntity());
        assertThat(balanceOf(payer)).isEqualByComparingTo("100.00");
    }

    @Test
    void openAmountBillsAcceptValuesWithinTheAllowedRange() throws Exception {
        var payer = newUserWithBalance("Open Amount", "100.00");
        String line = bankLine(0, TODAY.plusDays(5));
        stubQuote(line, new BillQuote("Charity", "12345678000199", TODAY.plusDays(5), null, 1_000, 500, 5_000,
                TODAY.plusDays(35), false));

        pay(payer, newKey(), line, "60.00").andExpect(status().isUnprocessableEntity());
        pay(payer, newKey(), line, "25.00").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.value").value(25.00));
        assertThat(balanceOf(payer)).isEqualByComparingTo("75.00");
    }

    @Test
    void rejectedPaymentIsReversedAndTheBillCanBePaidAgain() throws Exception {
        var payer = newUserWithBalance("Rejected Bill", "100.00");
        String line = bankLine(3_000, TODAY.plusDays(5));
        stubQuote(line, fixedQuote(3_000, 3_000, TODAY.plusDays(5), TODAY.plusDays(35)));
        when(billGateway.pay(forBarcode(line))).thenReturn(PaymentResult.rejected("Beneficiary account closed"));

        String id = JsonPath.read(pay(payer, newKey(), line, null).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "$.id");
        awaitStatus(payer, id, "FAILED");
        mvc.perform(get("/bills/payments/{id}", id).with(as(payer)))
                .andExpect(jsonPath("$.failureReason").value("Beneficiary account closed"));
        assertThat(balanceOf(payer)).isEqualByComparingTo("100.00");
        mvc.perform(get("/users/{id}/limits", payer.id()).with(as(payer)))
                .andExpect(jsonPath("$.usedToday").value(0.0));

        when(billGateway.pay(forBarcode(line))).thenReturn(PaymentResult.ok("AUTH-RETRY"));
        pay(payer, newKey(), line, null).andExpect(status().isAccepted());
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void partnerOutageKeepsThePaymentPendingUntilItSucceeds() throws Exception {
        var payer = newUserWithBalance("Outage Bill", "100.00");
        String line = bankLine(2_000, TODAY.plusDays(5));
        stubQuote(line, fixedQuote(2_000, 2_000, TODAY.plusDays(5), TODAY.plusDays(35)));
        when(billGateway.pay(forBarcode(line)))
                .thenThrow(new ExternalServiceException("Partner unavailable", null))
                .thenReturn(PaymentResult.ok("AUTH-LATER"));

        String id = JsonPath.read(pay(payer, newKey(), line, null).andReturn().getResponse().getContentAsString(), "$.id");

        awaitStatus(payer, id, "CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM bill_payments WHERE id = ?::uuid", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    void paysUtilityBills() throws Exception {
        var payer = newUserWithBalance("Utility Payer", "100.00");
        String barcode = BoletoCodeTest.utilityBarcode('3', '8', 8_765, uniqueDigits(29));
        String line = BoletoCode.utilityBarcodeToLine(barcode);
        stubQuote(line, new BillQuote("Water Utility", "12345678000199", null, 8_765L, 8_765, 8_765, 8_765,
                TODAY.plusDays(10), false));

        pay(payer, newKey(), line, null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("UTILITY"));
        assertThat(balanceOf(payer)).isEqualByComparingTo("12.35");
    }

    @Test
    void insufficientFundsCreateNoPaymentAndOthersCannotSeePayments() throws Exception {
        var poor = newUserWithBalance("Poor Bill Payer", "10.00");
        var owner = newUserWithBalance("Bill Owner", "100.00");
        var stranger = newUser(UserType.COMMON, "Bill Stranger");
        String expensive = bankLine(5_000, TODAY.plusDays(5));
        String cheap = bankLine(1_000, TODAY.plusDays(5));
        stubQuote(expensive, fixedQuote(5_000, 5_000, TODAY.plusDays(5), TODAY.plusDays(35)));
        stubQuote(cheap, fixedQuote(1_000, 1_000, TODAY.plusDays(5), TODAY.plusDays(35)));

        pay(poor, newKey(), expensive, null).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/bills/payments").with(as(poor))).andExpect(jsonPath("$.content.length()").value(0));

        String id = JsonPath.read(pay(owner, newKey(), cheap, null).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/bills/payments/{id}", id).with(as(stranger))).andExpect(status().isNotFound());
    }

    private void stubQuote(String line, BillQuote quote) {
        String barcode = BoletoCode.parse(line, TODAY).barcode();
        when(billGateway.lookup(barcode)).thenReturn(Optional.of(quote));
    }

    /**
     * Matches only this test's bill. Earlier tests may leave payments pending that the worker settles in the
     * background; a catch-all stub would let them consume this test's scripted responses.
     */
    private static BillGateway.BillOrder forBarcode(String line) {
        String barcode = BoletoCode.parse(line, TODAY).barcode();
        return argThat(order -> order != null && barcode.equals(order.barcode()));
    }

    private static BillQuote fixedQuote(long nominal, long due, LocalDate dueDate, LocalDate deadline) {
        return new BillQuote("City Power Company", "12345678000199", dueDate, nominal, due, due, due, deadline, false);
    }

    /** A valid, unique bank boleto line; the random free field keeps tests from sharing a barcode. */
    private static String bankLine(long amountCents, LocalDate due) {
        return BoletoCode.bankBarcodeToLine(BoletoCodeTest.bankBarcode("341", due, amountCents, uniqueDigits(25)));
    }

    private static String uniqueDigits(int length) {
        var digits = new StringBuilder();
        while (digits.length() < length) {
            digits.append(ThreadLocalRandom.current().nextInt(10));
        }
        return digits.toString();
    }

    private ResultActions lookup(UserResponse user, String code) throws Exception {
        return mvc.perform(post("/bills/lookup").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(code)));
    }

    private ResultActions pay(UserResponse payer, String key, String code, String value) throws Exception {
        String json = value == null ? "{\"code\": \"%s\"}".formatted(code)
                : "{\"code\": \"%s\", \"value\": %s}".formatted(code, value);
        return mvc.perform(post("/bills/payments").with(as(payer)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void awaitStatus(UserResponse payer, String id, String expected) {
        await().atMost(ASYNC).untilAsserted(() ->
                mvc.perform(get("/bills/payments/{id}", id).with(as(payer)))
                        .andExpect(jsonPath("$.status").value(expected)));
    }
}
