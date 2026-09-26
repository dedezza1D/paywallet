package br.com.paywallet.merchant;

import java.time.Instant;
import java.util.UUID;

public record ChargePaidEvent(
        UUID chargeId,
        Long merchantId,
        String merchantEmail,
        String reference,
        long amountCents,
        long netCents,
        PaymentMethod method,
        Instant paidAt) {

    public static final String TYPE = "ChargePaid";
}
