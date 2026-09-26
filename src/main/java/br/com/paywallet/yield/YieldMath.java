package br.com.paywallet.yield;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Sub-cent yield arithmetic. Amounts are in cents; rates in percent. */
final class YieldMath {

    static final int SCALE = 10;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private YieldMath() {
    }

    record Accrual(BigDecimal accrued, long credited, BigDecimal carry) {
    }

    /**
     * "X% of the CDI" applies X% to the daily CDI rate. Whole cents are credited and the fraction is carried,
     * so no yield is lost to rounding.
     */
    static Accrual accrue(long balanceCents, BigDecimal cdiDailyRatePercent, BigDecimal cdiPercentage,
                          BigDecimal carryIn) {
        BigDecimal accrued = BigDecimal.valueOf(balanceCents)
                .multiply(cdiDailyRatePercent).divide(HUNDRED)
                .multiply(cdiPercentage).divide(HUNDRED)
                .setScale(SCALE, RoundingMode.DOWN);
        BigDecimal total = accrued.add(carryIn);
        long credited = total.setScale(0, RoundingMode.DOWN).longValueExact();
        return new Accrual(accrued, credited, total.subtract(BigDecimal.valueOf(credited)));
    }

    /** Annual rate in percent equivalent to a daily rate, over the 252 business days of the Brazilian convention. */
    static BigDecimal annualized(BigDecimal cdiDailyRatePercent, BigDecimal cdiPercentage) {
        BigDecimal daily = cdiDailyRatePercent.multiply(cdiPercentage).divide(HUNDRED).divide(HUNDRED);
        return BigDecimal.ONE.add(daily).pow(252, MathContext.DECIMAL64).subtract(BigDecimal.ONE)
                .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }
}
