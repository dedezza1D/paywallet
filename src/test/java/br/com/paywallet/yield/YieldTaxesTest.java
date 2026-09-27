package br.com.paywallet.yield;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.paywallet.yield.YieldTaxes.Lot;

class YieldTaxesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

    @Test
    void iofFallsFromNinetySixPercentToZeroOnTheThirtiethDay() {
        assertThat(YieldTaxes.iofPercent(0)).isEqualTo(96);
        assertThat(YieldTaxes.iofPercent(1)).isEqualTo(96);
        assertThat(YieldTaxes.iofPercent(15)).isEqualTo(50);
        assertThat(YieldTaxes.iofPercent(29)).isEqualTo(3);
        assertThat(YieldTaxes.iofPercent(30)).isZero();
        assertThat(YieldTaxes.iofPercent(400)).isZero();
    }

    @Test
    void incomeTaxDecreasesWithTheHoldingPeriod() {
        assertThat(YieldTaxes.incomeTaxPercent(180)).isEqualByComparingTo("22.5");
        assertThat(YieldTaxes.incomeTaxPercent(181)).isEqualByComparingTo("20");
        assertThat(YieldTaxes.incomeTaxPercent(360)).isEqualByComparingTo("20");
        assertThat(YieldTaxes.incomeTaxPercent(361)).isEqualByComparingTo("17.5");
        assertThat(YieldTaxes.incomeTaxPercent(720)).isEqualByComparingTo("17.5");
        assertThat(YieldTaxes.incomeTaxPercent(721)).isEqualByComparingTo("15");
    }

    @Test
    void youngMoneyPaysIofFirstAndIncomeTaxOnTheRest() {
        var result = YieldTaxes.withhold(1_000, List.of(new Lot(TODAY.minusDays(10), 500_000)), TODAY);

        assertThat(result.iof()).isEqualTo(660);          // 66% of 1000
        assertThat(result.incomeTax()).isEqualTo(76);     // 22.5% of 340 = 76.5, rounded down
        assertThat(result.net()).isEqualTo(264);
    }

    @Test
    void eachLotIsTaxedByItsOwnAge() {
        var lots = List.of(new Lot(TODAY.minusDays(800), 300_000), new Lot(TODAY.minusDays(5), 100_000));

        var result = YieldTaxes.withhold(400, lots, TODAY);

        // 300 cents from the old lot at 15%; 100 cents from the new one: 83% IOF, then 22.5% of 17.
        assertThat(result.iof()).isEqualTo(83);
        assertThat(result.incomeTax()).isEqualTo(45 + 3);
        assertThat(result.net()).isEqualTo(400 - 83 - 48);
    }

    @Test
    void sharesAddUpToTheGrossExactly() {
        var lots = List.of(new Lot(TODAY.minusDays(900), 1), new Lot(TODAY.minusDays(800), 1),
                new Lot(TODAY.minusDays(700), 1));

        var result = YieldTaxes.withhold(100, lots, TODAY);

        assertThat(result.gross()).isEqualTo(100);
        assertThat(result.net() + result.incomeTax() + result.iof()).isEqualTo(100);
    }

    @Test
    void withoutLotsTheMoneyIsTreatedAsNew() {
        var result = YieldTaxes.withhold(100, List.of(), TODAY);

        assertThat(result.iof()).isEqualTo(96);
        assertThat(result.incomeTax()).isZero();
        assertThat(result.net()).isEqualTo(4);
    }
}
