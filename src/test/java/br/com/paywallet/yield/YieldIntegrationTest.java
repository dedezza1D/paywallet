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
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.user.UserType;
import br.com.paywallet.wallet.WalletDtos.TransferRequest;

/**
 * Each run credits every eligible account in the database, so tests use distinct future dates: the end-of-day
 * balance of a future date already includes everything posted now, and no run date is shared between tests.
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
        assertThat(balanceOf(saver)).isEqualByComparingTo("1000.50");
        mvc.perform(get("/users/{id}/statement", saver.id()).with(as(saver)))
                .andExpect(jsonPath("$.content[0].type").value("YIELD_CREDIT"))
                .andExpect(jsonPath("$.content[0].value").value(0.50))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(1000.50));
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void runningTheSameDayAgainCreditsNothingTwice() {
        var saver = newUserWithBalance("Twice Saver", "1000.00");
        LocalDate day = uniqueDay();

        yield.runFor(day);
        yield.runFor(day);

        assertThat(balanceOf(saver)).isEqualByComparingTo("1000.50");
    }

    @Test
    void yieldCompoundsFromDayToDay() {
        var saver = newUserWithBalance("Compound Saver", "10000.00");

        yield.runFor(uniqueDay());   // 10000.00 * 0.05% = 5.00
        yield.runFor(uniqueDay());   // 10005.00 * 0.05% = 5.0025 -> 5.00, carry 0.25 cent

        assertThat(balanceOf(saver)).isEqualByComparingTo("10010.00");
        assertThat(jdbc.queryForObject("""
                SELECT carry FROM yield_accruals y JOIN accounts a ON a.id = y.account_id
                 WHERE a.owner_id = ? ORDER BY reference_date DESC LIMIT 1
                """, BigDecimal.class, saver.id())).isEqualByComparingTo("0.25");
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
                .andExpect(jsonPath("$.totalCredited").value(1.00))
                .andExpect(jsonPath("$.history[0].endOfDayBalance").value(2000.00))
                .andExpect(jsonPath("$.history[0].credited").value(1.00));
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

    private static LocalDate uniqueDay() {
        return LocalDate.now().plusDays(NEXT_DAY.getAndIncrement());
    }
}
