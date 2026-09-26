package br.com.paywallet.yield;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class YieldMathTest {

    private static final BigDecimal RATE = new BigDecimal("0.05");

    @Test
    void appliesTheShareOfTheCdiToTheDailyRate() {
        assertThat(YieldMath.accrue(100_000, RATE, new BigDecimal("100"), BigDecimal.ZERO).credited()).isEqualTo(50);
        assertThat(YieldMath.accrue(100_000, RATE, new BigDecimal("102"), BigDecimal.ZERO).credited()).isEqualTo(51);
    }

    /** R$ 1.00 earns 0.05 cent a day: nothing is lost, one cent is credited on the 20th day. */
    @Test
    void carriesFractionsOfACentUntilTheyAddUpToACent() {
        BigDecimal carry = BigDecimal.ZERO;
        long credited = 0;
        for (int day = 1; day <= 19; day++) {
            var accrual = YieldMath.accrue(100, RATE, new BigDecimal("100"), carry);
            credited += accrual.credited();
            carry = accrual.carry();
        }
        assertThat(credited).isZero();
        assertThat(carry).isEqualByComparingTo("0.95");

        var day20 = YieldMath.accrue(100, RATE, new BigDecimal("100"), carry);
        assertThat(day20.credited()).isEqualTo(1);
        assertThat(day20.carry()).isEqualByComparingTo("0");
    }

    @Test
    void annualizesOverTwoHundredFiftyTwoBusinessDays() {
        assertThat(YieldMath.annualized(new BigDecimal("0.055131"), new BigDecimal("100"))).isEqualByComparingTo("14.90");
        assertThat(YieldMath.annualized(RATE, new BigDecimal("100"))).isEqualByComparingTo("13.42");
    }
}
