package br.com.paywallet.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeeTest {

    @Test
    void splitsTheAmountIntoFeeAndNet() {
        var fee = Fee.of(10_000, 199);
        assertThat(fee.feeCents()).isEqualTo(199);
        assertThat(fee.netCents()).isEqualTo(9_801);
    }

    @Test
    void roundsHalfUpToTheCent() {
        assertThat(Fee.of(1_234, 199).feeCents()).isEqualTo(25);   // 24.5566
        assertThat(Fee.of(25, 199).feeCents()).isZero();           // 0.4975
        assertThat(Fee.of(1_234, 199).netCents()).isEqualTo(1_209);
    }
}
