package br.com.paywallet.wallet;

import java.time.Instant;
import java.util.UUID;

import br.com.paywallet.feed.Visibility;

/** Written to the outbox with the ledger movement and relayed to Kafka; consumed by the feed and notifications. */
public record TransferCompletedEvent(
        UUID transactionId,
        Long payerId,
        String payerName,
        Long payeeId,
        String payeeName,
        String payeeEmail,
        long amountCents,
        String message,
        Visibility visibility,
        Instant createdAt) {

    public static final String TYPE = "TransferCompleted";
}
