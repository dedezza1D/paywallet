package br.com.paywallet.feed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Social activity metadata (message, visibility). Not financial data: the ledger is the source of truth for money. */
@Document("feed")
@CompoundIndex(name = "participant_created", def = "{'participantIds': 1, 'createdAt': -1}")
@CompoundIndex(name = "visibility_created", def = "{'visibility': 1, 'createdAt': -1}")
public record FeedEntry(
        @Id String id,
        /** Unique so that reprocessing the same event does not duplicate the activity. */
        @Indexed(unique = true) UUID transactionId,
        Long payerId,
        String payerName,
        Long payeeId,
        String payeeName,
        List<Long> participantIds,
        long amountCents,
        String message,
        Visibility visibility,
        Instant createdAt) {
}
