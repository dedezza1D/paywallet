package br.com.paywallet.feed;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.paywallet.ledger.Money;
import br.com.paywallet.messaging.Topics;
import br.com.paywallet.wallet.TransferCompletedEvent;

@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    private final FeedRepository repository;
    private final ObjectMapper objectMapper;

    public FeedService(FeedRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Participant view: includes the amount. */
    public record FeedItem(String id, UUID transactionId, Long payerId, String payerName, Long payeeId,
                           String payeeName, BigDecimal value, String message, Visibility visibility,
                           Instant createdAt) {

        static FeedItem from(FeedEntry e) {
            return new FeedItem(e.id(), e.transactionId(), e.payerId(), e.payerName(), e.payeeId(),
                    e.payeeName(), Money.fromCents(e.amountCents()), e.message(), e.visibility(), e.createdAt());
        }
    }

    /** Public view: no amount and no internal transaction ids. */
    public record PublicFeedItem(String payerName, String payeeName, String message, Instant createdAt) {

        static PublicFeedItem from(FeedEntry e) {
            return new PublicFeedItem(e.payerName(), e.payeeName(), e.message(), e.createdAt());
        }
    }

    /** Idempotent: the unique index on transactionId absorbs redelivered events. */
    @KafkaListener(topics = Topics.TRANSFERS_COMPLETED, groupId = "feed")
    public void onTransferCompleted(String payload) throws JsonProcessingException {
        var event = objectMapper.readValue(payload, TransferCompletedEvent.class);
        var entry = new FeedEntry(null, event.transactionId(), event.payerId(), event.payerName(),
                event.payeeId(), event.payeeName(), List.of(event.payerId(), event.payeeId()),
                event.amountCents(), event.message(), event.visibility(), event.createdAt());
        try {
            repository.save(entry);
        } catch (DuplicateKeyException e) {
            log.debug("Activity for transaction {} already recorded", event.transactionId());
        }
    }

    public Page<FeedItem> userFeed(Long userId, Pageable pageable) {
        return repository.findByParticipantIds(userId, pageable).map(FeedItem::from);
    }

    public Page<PublicFeedItem> publicFeed(Pageable pageable) {
        return repository.findByVisibility(Visibility.PUBLIC, pageable).map(PublicFeedItem::from);
    }
}
