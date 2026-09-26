package br.com.paywallet.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;
import br.com.paywallet.wallet.WalletDtos.TransferRequest;

/**
 * With the bureau at 800 and a R$ 5,000 deposit on a new account, the score is 680 (band B, 3.49% a month) and
 * the limit R$ 4,900 (three times the monthly inflow, rounded down to R$ 100).
 */
class LoanIntegrationTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

    @Autowired LoanService loanService;
    @Autowired LedgerService ledger;

    @Test
    void analysisExplainsTheDecisionAndIsReusedUntilItExpires() throws Exception {
        var borrower = newUserWithBalance("Analyzed", "5000.00");

        String first = mvc.perform(get("/credit/analysis").with(as(borrower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.score").value(680))
                .andExpect(jsonPath("$.riskBand").value("B"))
                .andExpect(jsonPath("$.creditLimit").value(4900.00))
                .andExpect(jsonPath("$.monthlyRate").value(3.49))
                .andExpect(jsonPath("$.reasons", hasItems("Account opened less than 30 days ago",
                        "No repayment history on this platform")))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get("/credit/analysis").with(as(borrower))).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        verify(creditBureau, times(1)).report(borrower.document());
    }

    @Test
    void bureauRestrictionsAndLowScoresAreRejected() throws Exception {
        var restricted = newUserWithBalance("Restricted", "5000.00");
        var lowScore = newUserWithBalance("Low Score", "5000.00");
        doReturn(new CreditBureau.BureauReport(900, true)).when(creditBureau).report(restricted.document());
        doReturn(new CreditBureau.BureauReport(100, false)).when(creditBureau).report(lowScore.document());

        mvc.perform(get("/credit/analysis").with(as(restricted)))
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.reasons[0]").value("Overdue debts registered at the credit bureau"));
        mvc.perform(get("/credit/analysis").with(as(lowScore)))
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.reasons[0]").value("Score below the minimum for credit"));
        simulate(restricted, "1000", 12).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void merchantsAreNotEligible() throws Exception {
        var merchant = newUser(UserType.MERCHANT, "Credit Shop");
        mvc.perform(get("/credit/analysis").with(as(merchant))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void simulationShowsIofCetAndTheFullSchedule() throws Exception {
        var borrower = newUserWithBalance("Simulator", "5000.00");

        String body = simulate(borrower, "1000", 12)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installments").value(12))
                .andExpect(jsonPath("$.monthlyRate").value(3.49))
                .andExpect(jsonPath("$.schedule.length()").value(12))
                .andExpect(jsonPath("$.schedule[0].dueDate").value(TODAY.plusMonths(1).toString()))
                .andReturn().getResponse().getContentAsString();

        BigDecimal iof = new BigDecimal(JsonPath.read(body, "$.iof").toString());
        assertThat(iof).isBetween(new BigDecimal("3.81"), new BigDecimal("33.73"));
        assertThat(new BigDecimal(JsonPath.read(body, "$.financed").toString())).isEqualByComparingTo(iof.add(new BigDecimal("1000")));
        assertThat(new BigDecimal(JsonPath.read(body, "$.cetAnnual").toString())).isGreaterThan(new BigDecimal("50.93"));
        simulate(borrower, "1000", 36).andExpect(status().isUnprocessableEntity());
        simulate(borrower, "50", 12).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void contractDisbursesIntoTheWalletAndBooksTheLedger() throws Exception {
        var borrower = newUserWithBalance("Borrower", "5000.00");
        long taxBefore = balance(AccountType.TAX_PAYABLE_ACCOUNT_ID);
        String key = newKey();

        String body = contract(borrower, key, "1000", 12)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.schedule[0].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long iofCents = new BigDecimal(JsonPath.read(body, "$.iof").toString()).movePointRight(2).longValueExact();

        assertThat(balanceOf(borrower)).isEqualByComparingTo("6000.00");
        assertThat(balance(AccountType.TAX_PAYABLE_ACCOUNT_ID) - taxBefore).isEqualTo(iofCents);
        assertThat(ledger.reconcile().consistent()).isTrue();

        contract(borrower, key, "1000", 12).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(body, "$.id")));
        assertThat(balanceOf(borrower)).isEqualByComparingTo("6000.00");

        // The outstanding principal (amount + IOF) is taken from the available limit.
        mvc.perform(get("/credit/analysis").with(as(borrower)))
                .andExpect(jsonPath("$.available").value(new BigDecimal("3900.00").subtract(new BigDecimal(iofCents).movePointLeft(2)).doubleValue()));
    }

    @Test
    void theCreditLimitCannotBeExceeded() throws Exception {
        var borrower = newUserWithBalance("Limit", "5000.00");

        contract(borrower, newKey(), "5000", 12).andExpect(status().isUnprocessableEntity());
        contract(borrower, newKey(), "4000", 12).andExpect(status().isCreated());
        contract(borrower, newKey(), "1000", 12).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("Amount exceeds the available credit limit")));
    }

    @Test
    void collectionDebitsDueInstallmentsAndBooksInterest() throws Exception {
        var borrower = newUserWithBalance("Payer On Time", "5000.00");
        String loan = contract(borrower, newKey(), "1000", 3).andReturn().getResponse().getContentAsString();
        String loanId = JsonPath.read(loan, "$.id");
        BigDecimal first = new BigDecimal(JsonPath.read(loan, "$.schedule[0].amount").toString());

        loanService.collect(TODAY.plusMonths(1));

        assertThat(balanceOf(borrower)).isEqualByComparingTo(new BigDecimal("6000.00").subtract(first));
        mvc.perform(get("/loans/{id}", loanId).with(as(borrower)))
                .andExpect(jsonPath("$.schedule[0].status").value("PAID"))
                .andExpect(jsonPath("$.schedule[0].lateCharges").value(0.0))
                .andExpect(jsonPath("$.schedule[1].status").value("PENDING"));
        // Collection runs over every loan in the database, so check this installment's own ledger movement.
        long interest = new BigDecimal(JsonPath.read(loan, "$.schedule[0].interest").toString()).movePointRight(2).longValueExact();
        assertThat(jdbc.queryForObject("""
                SELECT p.amount FROM postings p JOIN loan_installments i ON i.ledger_transaction_id = p.transaction_id
                 WHERE i.loan_id = ?::uuid AND i.number = 1 AND p.account_id = ?
                """, Long.class, loanId, AccountType.INTEREST_INCOME_ACCOUNT_ID)).isEqualTo(interest);
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void missingBalanceMakesTheInstallmentOverdueAndLatePaymentChargesFineAndInterest() throws Exception {
        // A smaller deposit keeps the drain below the R$ 5,000 daily transfer limit.
        var borrower = newUserWithBalance("Late Payer", "1000.00");
        var friend = newUser(UserType.COMMON, "Friend Of Late Payer");
        String loan = contract(borrower, newKey(), "600", 3).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String loanId = JsonPath.read(loan, "$.id");
        BigDecimal installment = new BigDecimal(JsonPath.read(loan, "$.schedule[0].amount").toString());
        walletService.transfer(borrower.id(), new TransferRequest(new BigDecimal("1590.00"), friend.id(), null, null), newKey());
        LocalDate due = TODAY.plusMonths(1);

        loanService.collect(due);

        mvc.perform(get("/loans/{id}", loanId).with(as(borrower)))
                .andExpect(jsonPath("$.schedule[0].status").value("OVERDUE"));
        verify(notificationClient, timeout(20_000)).send(eq(borrower.email()), contains("is overdue"));

        walletService.deposit(borrower.id(), new BigDecimal("1000.00"), newKey());
        loanService.collect(due.plusDays(15));

        // 2% fine + 1% a month for 15 days = 2.5% of the installment.
        BigDecimal late = installment.multiply(new BigDecimal("0.025")).setScale(2, RoundingMode.HALF_UP);
        mvc.perform(get("/loans/{id}", loanId).with(as(borrower)))
                .andExpect(jsonPath("$.schedule[0].status").value("PAID"))
                .andExpect(jsonPath("$.schedule[0].lateCharges").value(late.doubleValue()));
        assertThat(balanceOf(borrower)).isEqualByComparingTo(new BigDecimal("1010.00").subtract(installment).subtract(late));

        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void manualPaymentsGoOldestFirstAndPayOffTheLoan() throws Exception {
        var borrower = newUserWithBalance("Early Payer", "5000.00");
        var stranger = newUser(UserType.COMMON, "Loan Stranger");
        String loanId = JsonPath.read(contract(borrower, newKey(), "600", 3).andReturn().getResponse().getContentAsString(), "$.id");

        pay(borrower, loanId, 2).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Pay installment 1 first"));
        pay(borrower, loanId, 1).andExpect(status().isOk()).andExpect(jsonPath("$.lateCharges").value(0.0));
        BigDecimal afterFirst = balanceOf(borrower);
        pay(borrower, loanId, 1).andExpect(status().isUnprocessableEntity());
        assertThat(balanceOf(borrower)).isEqualByComparingTo(afterFirst);

        pay(borrower, loanId, 2).andExpect(status().isOk());
        pay(borrower, loanId, 3).andExpect(status().isOk()).andExpect(jsonPath("$.loanStatus").value("PAID_OFF"));

        mvc.perform(get("/loans/{id}", loanId).with(as(borrower)))
                .andExpect(jsonPath("$.status").value("PAID_OFF"))
                .andExpect(jsonPath("$.outstandingPrincipal").value(0.0));
        mvc.perform(get("/loans/{id}", loanId).with(as(stranger))).andExpect(status().isNotFound());
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    private ResultActions simulate(UserResponse user, String value, int installments) throws Exception {
        return mvc.perform(post("/loans/simulations").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": %s, \"installments\": %d}".formatted(value, installments)));
    }

    private ResultActions contract(UserResponse user, String key, String value, int installments) throws Exception {
        return mvc.perform(post("/loans").with(as(user)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": %s, \"installments\": %d}".formatted(value, installments)));
    }

    private ResultActions pay(UserResponse user, String loanId, int number) throws Exception {
        return mvc.perform(post("/loans/{id}/installments/{n}/payment", loanId, number).with(as(user)));
    }

    private long balance(UUID account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, account);
    }
}
