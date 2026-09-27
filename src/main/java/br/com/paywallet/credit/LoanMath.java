package br.com.paywallet.credit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Consumer loan arithmetic. Amounts in cents, rates as fractions (0.0349 = 3.49%). */
final class LoanMath {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int IOF_MAX_DAYS = 365;

    private LoanMath() {
    }

    record Installment(int number, LocalDate dueDate, long amount, long principal, long interest) {
    }

    record Quote(long amount, long iof, long financed, BigDecimal monthlyRate, List<Installment> schedule,
                 BigDecimal cetMonthly, BigDecimal cetAnnual) {

        long totalPayable() {
            return schedule.stream().mapToLong(Installment::amount).sum();
        }
    }

    /**
     * Price table (constant installments) on the amount plus financed IOF. IOF for individuals is a flat
     * percentage plus a daily percentage on each installment's principal for the days until its due date, capped
     * at 365 days; it is estimated on the schedule of the requested amount.
     */
    static Quote quote(long amount, int installments, BigDecimal monthlyRate, LocalDate contractDate,
                       LocalDate firstDueDate, BigDecimal iofFlatPercent, BigDecimal iofDailyPercent) {
        List<LocalDate> dueDates = new ArrayList<>();
        for (int k = 0; k < installments; k++) {
            dueDates.add(firstDueDate.plusMonths(k));
        }
        BigDecimal iof = BigDecimal.valueOf(amount).multiply(iofFlatPercent).divide(HUNDRED, MC);
        for (Installment base : price(amount, monthlyRate, dueDates)) {
            long days = Math.min(ChronoUnit.DAYS.between(contractDate, base.dueDate()), IOF_MAX_DAYS);
            iof = iof.add(BigDecimal.valueOf(base.principal()).multiply(iofDailyPercent).divide(HUNDRED, MC)
                    .multiply(BigDecimal.valueOf(days)));
        }
        long iofCents = iof.setScale(0, RoundingMode.HALF_UP).longValueExact();
        long financed = amount + iofCents;
        List<Installment> schedule = price(financed, monthlyRate, dueDates);
        BigDecimal cetAnnual = cetAnnual(amount, schedule, contractDate);
        BigDecimal cetMonthly = BigDecimal.valueOf(Math.pow(1 + cetAnnual.doubleValue(), 1.0 / 12) - 1)
                .setScale(6, RoundingMode.HALF_UP);
        return new Quote(amount, iofCents, financed, monthlyRate, schedule, cetMonthly, cetAnnual);
    }

    /** Constant installment PMT = PV * i / (1 - (1 + i)^-n); the last one absorbs rounding. */
    static List<Installment> price(long principal, BigDecimal rate, List<LocalDate> dueDates) {
        int n = dueDates.size();
        BigDecimal factor = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(BigDecimal.ONE.add(rate).pow(n, MC), MC));
        long pmt = BigDecimal.valueOf(principal).multiply(rate).divide(factor, MC)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        List<Installment> schedule = new ArrayList<>();
        long balance = principal;
        for (int k = 1; k <= n; k++) {
            long interest = BigDecimal.valueOf(balance).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long amortization = k == n ? balance : pmt - interest;
            schedule.add(new Installment(k, dueDates.get(k - 1), amortization + interest, amortization, interest));
            balance -= amortization;
        }
        return schedule;
    }

    /**
     * Total effective cost (CET) as the central bank defines it: the annual rate that discounts every installment,
     * at (days since contract / 365), to the amount the customer actually receives.
     */
    static BigDecimal cetAnnual(long received, List<Installment> schedule, LocalDate contractDate) {
        double low = 0;
        double high = 100;
        for (int i = 0; i < 200; i++) {
            double mid = (low + high) / 2;
            double presentValue = 0;
            for (Installment installment : schedule) {
                double years = ChronoUnit.DAYS.between(contractDate, installment.dueDate()) / 365.0;
                presentValue += installment.amount() / Math.pow(1 + mid, years);
            }
            if (presentValue > received) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return BigDecimal.valueOf((low + high) / 2).setScale(6, RoundingMode.HALF_UP);
    }

    /** Fine on the installment plus default interest pro rata per day (monthly rate / 30). */
    /**
     * Value today of an installment paid before its due date. The consumer protection code (CDC art. 52) requires
     * a proportional reduction of interest, applied by discounting at the contract rate over days/30 months.
     */
    static long presentValue(long amount, BigDecimal monthlyRate, long daysUntilDue) {
        if (daysUntilDue <= 0) {
            return amount;
        }
        double factor = Math.pow(1 + monthlyRate.doubleValue(), daysUntilDue / 30.0);
        return BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(factor), MC)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static long lateCharges(long installmentAmount, long daysLate, BigDecimal finePercent, BigDecimal monthlyInterestPercent) {
        if (daysLate <= 0) {
            return 0;
        }
        BigDecimal amount = BigDecimal.valueOf(installmentAmount);
        BigDecimal fine = amount.multiply(finePercent).divide(HUNDRED, MC);
        BigDecimal interest = amount.multiply(monthlyInterestPercent).divide(HUNDRED, MC)
                .multiply(BigDecimal.valueOf(daysLate)).divide(BigDecimal.valueOf(30), MC);
        return fine.add(interest).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
