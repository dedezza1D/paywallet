package br.com.paywallet.pix;

import java.time.Instant;

public record PixReceivedEvent(
        String endToEndId,
        Long payeeUserId,
        String payeeEmail,
        String payerName,
        long amountCents,
        Instant createdAt) {

    public static final String TYPE = "PixReceived";
}
