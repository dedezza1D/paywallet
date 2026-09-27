package br.com.paywallet.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class LoanMathTest {

    private static final LocalDate CONTRACT = LocalDate.of(2026, 9, 26);
    private static final BigDecimal RATE = new BigDecimal("0.0349");
    private static final BigDecimal IOF_FLAT = new BigDecimal("0.38");
    private static final BigDecimal IOF_DAILY = new BigDecimal("0.0082");

    @Test
    void priceTableHasConstantInstallmentsThatRepayThePrincipal() {
        var dueDates = List.of(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 26));
        var schedule = LoanMath.price(100_000, new BigDecimal("0.02"), dueDates);

        // PMT = 1000 * 0.02 / (1 - 1.02^-3) = 346.75. Balances 673.25 then 339.97; the last installment absorbs
        // the rounding: 339.97 + 6.80 interest = 346.77.
        assertThat(schedule).extracting(LoanMath.Installment::amount).containsExactly(34_675L, 34_675L, 34_677L);
        assertThat(schedule.get(0).interest()).isEqualTo(2_000);
        assertThat(schedule.stream().mapToLong(LoanMath.Installment::principal).sum()).isEqualTo(100_000);
        schedule.forEach(i -> assertThat(i.amount()).isEqualTo(i.principal() + i.interest()));
    }

    @Test
    void quoteFinancesTheIofAndReportsACetAboveTheNominalRate() {
        var quote = LoanMath.quote(100_000, 12, RATE, CONTRACT, CONTRACT.plusMonths(1), IOF_FLAT, IOF_DAILY);

        // IOF: 0.38% flat (R$ 3.80) plus 0.0082%/day on each principal, at most 365 days (at most R$ 29.93).
        assertThat(quote.iof()).isBetween(380L + 1, 380L + 2_993);
        assertThat(quote.financed()).isEqualTo(100_000 + quote.iof());
        assertThat(quote.schedule()).hasSize(12);
        assertThat(quote.schedule().stream().mapToLong(LoanMath.Installment::principal).sum()).isEqualTo(quote.financed());
        assertThat(quote.schedule().get(11).dueDate()).isEqualTo(LocalDate.of(2027, 9, 26));

        double nominalAnnual = Math.pow(1.0349, 12) - 1;   // 50.93%
        assertThat(quote.cetAnnual().doubleValue()).isGreaterThan(nominalAnnual);
        assertThat(quote.cetMonthly().doubleValue()).isGreaterThan(0.0349).isLessThan(0.04);
    }

    @Test
    void cetEqualsTheRateWhenThereAreNoCosts() {
        var dueDates = List.of(CONTRACT.plusDays(365));
        var schedule = LoanMath.price(100_000, new BigDecimal("0.10"), dueDates);

        assertThat(LoanMath.cetAnnual(100_000, schedule, CONTRACT)).isEqualByComparingTo("0.100000");
    }

    @Test
    void earlyInstallmentsAreDiscountedAtTheContractRate() {
        // R$ 100.00 due in 30 days at 3.49% a month: 100 / 1.0349 = 96.63; due in 45 days: 100 / 1.0349^1.5 = 94.98
        assertThat(LoanMath.presentValue(10_000, RATE, 30)).isEqualTo(9_663);
        assertThat(LoanMath.presentValue(10_000, RATE, 45)).isEqualTo(9_498);
        assertThat(LoanMath.presentValue(10_000, RATE, 0)).isEqualTo(10_000);
        assertThat(LoanMath.presentValue(10_000, RATE, -5)).isEqualTo(10_000);
    }

    @Test
    void lateChargesAreAFineAndDailyDefaultInterest() {
        // R$ 100.00, 15 days late: 2% fine (2.00) + 1%/month * 15/30 (0.50)
        assertThat(LoanMath.lateCharges(10_000, 15, new BigDecimal("2"), new BigDecimal("1"))).isEqualTo(250);
        assertThat(LoanMath.lateCharges(10_000, 0, new BigDecimal("2"), new BigDecimal("1"))).isZero();
    }
}
