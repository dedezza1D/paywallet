package br.com.paywallet.yield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.user.UserType;
import br.com.paywallet.wallet.WalletDtos.TransferRequest;

/**
 * Each run credits every eligible account in the database, so tests use distinct future dates: the end-of-day
 * balance of a future date already includes everything posted now, and no run date is shared between tests.
 * Money deposited today is over 720 days old on those dates, so yield is credited net of 15% income tax.
 */
class YieldIntegrationTest extends IntegrationTest {

    private static final AtomicInteger NEXT_DAY = new AtomicInteger(1_000);

    @Autowired YieldService yield;
    @Autowired LedgerService ledger;

    @Test
    void creditsTheDailyShareOfTheCdiOnTheEndOfDayBalance() throws Exception {
        var saver = newUserWithBalance("Saver", "1000.00");
        LocalDate day = uniqueDay();

        var run = yield.runFor(day);

        assertThat(run.accounts()).isPositive();
        // Gross 0.50, income tax 15% = 0.075, rounded down to 0.07.
        assertThat(balanceOf(saver)).isEqualByComparingTo("1000.43");
        mvc.perform(get("/users/{id}/statement", saver.id()).with(as(saver)))
                .andExpect(jsonPath("$.content[0].type").value("YIELD_CREDIT"))
                .andExpect(jsonPath("$.content[0].value").value(0.43))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(1000.43));
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void runningTheSameDayAgainCreditsNothingTwice() {
        var saver = newUserWithBalance("Twice Saver", "1000.00");
        LocalDate day = uniqueDay();

        yield.runFor(day);
        yield.runFor(day);

        assertThat(balanceOf(saver)).isEqualByComparingTo("1000.43");
    }

    @Test
    void yieldCompoundsFromDayToDay() {
        var saver = newUserWithBalance("Compound Saver", "10000.00");

        yield.runFor(uniqueDay());   // 10000.00 * 0.05% = 5.00 gross, 4.25 net
        yield.runFor(uniqueDay());   // 10004.25 * 0.05% = 5.002125 -> 5.00 gross, carry 0.2125 cent

        assertThat(balanceOf(saver)).isEqualByComparingTo("10008.50");
        assertThat(jdbc.queryForObject("""
                SELECT carry FROM yield_accruals y JOIN accounts a ON a.id = y.account_id
                 WHERE a.owner_id = ? ORDER BY reference_date DESC LIMIT 1
                """, BigDecimal.class, saver.id())).isEqualByComparingTo("0.2125");
    }

    @Test
    void merchantsAndEmptyWalletsDoNotEarn() {
        var merchant = newUser(UserType.MERCHANT, "Non Earning Shop");
        var empty = newUser(UserType.COMMON, "Empty Wallet");
        var buyer = newUserWithBalance("Shop Customer", "100.00");
        walletService.transfer(buyer.id(),
                new TransferRequest(new BigDecimal("100.00"), merchant.id(), null, null),
                newKey());

        yield.runFor(uniqueDay());

        assertThat(balanceOf(merchant)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(empty)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(buyer)).isEqualByComparingTo("0.00");
    }

    @Test
    void summaryShowsRateTotalsAndHistoryToTheOwnerOnly() throws Exception {
        var saver = newUserWithBalance("Summary Saver", "2000.00");
        var other = newUser(UserType.COMMON, "Nosy Neighbor");
        yield.runFor(uniqueDay());

        mvc.perform(get("/users/{id}/yield", saver.id()).with(as(saver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.cdiPercentage").value(100))
                .andExpect(jsonPath("$.annualRate").value(13.42))
                .andExpect(jsonPath("$.totalCredited").value(0.85))
                .andExpect(jsonPath("$.totalWithheld").value(0.15))
                .andExpect(jsonPath("$.history[0].endOfDayBalance").value(2000.00))
                .andExpect(jsonPath("$.history[0].gross").value(1.00))
                .andExpect(jsonPath("$.history[0].incomeTax").value(0.15))
                .andExpect(jsonPath("$.history[0].credited").value(0.85));
        mvc.perform(get("/users/{id}/yield", saver.id()).with(as(other))).andExpect(status().isForbidden());
    }

    @Test
    void operationsCanRunADayButOnlyBusinessDaysAndOnlyAsAdmin() throws Exception {
        var customer = newUser(UserType.COMMON, "Not Ops");
        var operator = newUser(UserType.COMMON, "Ops");
        makeAdmin(operator);
        LocalDate holiday = uniqueDay();
        LocalDate businessDay = uniqueDay();
        doReturn(new TreeMap<>()).when(cdiRates).dailyRates(eq(holiday), eq(holiday));

        mvc.perform(post("/admin/yield/runs").param("date", businessDay.toString()).with(as(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/yield/runs").param("date", holiday.toString()).with(as(operator)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/yield/runs").param("date", businessDay.toString()).with(as(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(businessDay.toString()))
                .andExpect(jsonPath("$.cdiDailyRate").value(0.05));
    }

    @Test
    void recentMoneyPaysIofAndTheHigherIncomeTax() {
        var saver = newUserWithBalance("Recent Saver", "10000.00");
        long taxPayable = accountBalance(AccountType.TAX_PAYABLE_ACCOUNT_ID);

        // Only test run within 30 days of today: 15 days old, IOF 50%, then 22.5% of the rest.
        yield.runFor(LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(15));

        assertThat(balanceOf(saver)).isEqualByComparingTo("10001.94");
        var accrual = jdbc.queryForMap("""
                SELECT gross, iof, income_tax, credited FROM yield_accruals y JOIN accounts a ON a.id = y.account_id
                 WHERE a.owner_id = ?
                """, saver.id());
        assertThat(accrual).containsEntry("gross", 500L).containsEntry("iof", 250L)
                .containsEntry("income_tax", 56L).containsEntry("credited", 194L);
        assertThat(accountBalance(AccountType.TAX_PAYABLE_ACCOUNT_ID) - taxPayable).isGreaterThanOrEqualTo(306);
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void lotsFollowTheBalanceAsMoneyLeaves() {
        var saver = newUserWithBalance("Spending Saver", "1000.00");
        var friend = newUser(UserType.COMMON, "Spending Friend");
        yield.runFor(uniqueDay());
        walletService.transfer(saver.id(), new TransferRequest(new BigDecimal("600.00"), friend.id(), null, null),
                newKey());

        yield.runFor(uniqueDay());

        long lots = jdbc.queryForObject("""
                SELECT coalesce(sum(l.amount), 0) FROM yield_lots l JOIN accounts a ON a.id = l.account_id
                 WHERE a.owner_id = ?
                """, Long.class, saver.id());
        long endOfDay = jdbc.queryForObject("""
                SELECT y.balance FROM yield_accruals y JOIN accounts a ON a.id = y.account_id
                 WHERE a.owner_id = ? ORDER BY reference_date DESC LIMIT 1
                """, Long.class, saver.id());
        assertThat(endOfDay).isEqualTo(40_043);
        assertThat(lots).isEqualTo(endOfDay);
    }

    private long accountBalance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private static LocalDate uniqueDay() {
        return LocalDate.now().plusDays(NEXT_DAY.getAndIncrement());
    }
}
