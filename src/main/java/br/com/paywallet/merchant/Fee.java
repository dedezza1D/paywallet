package br.com.paywallet.merchant;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Merchant discount rate applied to one payment, in cents. The payer always pays the full amount. */
public record Fee(int bps, long feeCents, long netCents) {

    public static Fee of(long amountCents, int bps) {
        long fee = BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return new Fee(bps, fee, amountCents - fee);
    }
}
