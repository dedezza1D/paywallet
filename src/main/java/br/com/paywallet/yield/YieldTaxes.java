package br.com.paywallet.yield;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Withholding on fixed-income gains in Brazil, by how long the money has been invested: IOF falls from 96% of the
 * gain on the first day to zero on the 30th, then income tax applies to what is left at 22.5% up to 180 days,
 * 20% up to 360, 17.5% up to 720 and 15% after that. Amounts are in cents; fractions are dropped in the
 * customer's favor.
 */
final class YieldTaxes {

    private static final int[] IOF_PERCENT = {96, 93, 90, 86, 83, 80, 76, 73, 70, 66, 63, 60, 56, 53, 50, 46, 43,
            40, 36, 33, 30, 26, 23, 20, 16, 13, 10, 6, 3};

    private YieldTaxes() {
    }

    record Lot(LocalDate date, long amount) {
    }

    record Withholding(long gross, long incomeTax, long iof, long net) {
    }

    static int iofPercent(long days) {
        long d = Math.max(1, days);
        return d > IOF_PERCENT.length ? 0 : IOF_PERCENT[(int) d - 1];
    }

    static BigDecimal incomeTaxPercent(long days) {
        if (days <= 180) {
            return new BigDecimal("22.5");
        }
        if (days <= 360) {
            return new BigDecimal("20");
        }
        return days <= 720 ? new BigDecimal("17.5") : new BigDecimal("15");
    }

    /**
     * Splits a day's gross yield over the lots in proportion to their amounts (largest remainder, so the parts add
     * up exactly) and taxes each part by its age on {@code date}. Without lots the money is treated as new.
     */
    static Withholding withhold(long gross, List<Lot> lots, LocalDate date) {
        List<Lot> basis = lots.stream().mapToLong(Lot::amount).sum() > 0 ? lots : List.of(new Lot(date, 1));
        long[] shares = allocate(gross, basis);
        long incomeTax = 0;
        long iof = 0;
        for (int i = 0; i < basis.size(); i++) {
            long days = ChronoUnit.DAYS.between(basis.get(i).date(), date);
            long share = shares[i];
            long lotIof = share * iofPercent(days) / 100;
            long lotIncomeTax = BigDecimal.valueOf(share - lotIof).multiply(incomeTaxPercent(days))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValueExact();
            iof += lotIof;
            incomeTax += lotIncomeTax;
        }
        return new Withholding(gross, incomeTax, iof, gross - incomeTax - iof);
    }

    private static long[] allocate(long total, List<Lot> lots) {
        long sum = lots.stream().mapToLong(Lot::amount).sum();
        long[] shares = new long[lots.size()];
        long[] remainders = new long[lots.size()];
        long allocated = 0;
        for (int i = 0; i < lots.size(); i++) {
            BigDecimal exact = BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(lots.get(i).amount()));
            shares[i] = exact.divide(BigDecimal.valueOf(sum), 0, RoundingMode.DOWN).longValueExact();
            remainders[i] = exact.remainder(BigDecimal.valueOf(sum)).longValueExact();
            allocated += shares[i];
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lots.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingLong((Integer i) -> remainders[i]).reversed());
        for (int k = 0; k < total - allocated; k++) {
            shares[order.get(k)]++;
        }
        return shares;
    }
}
