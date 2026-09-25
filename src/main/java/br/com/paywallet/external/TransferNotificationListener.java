package br.com.paywallet.external;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.paywallet.ledger.Money;
import br.com.paywallet.messaging.EventHeaders;
import br.com.paywallet.messaging.Topics;
import br.com.paywallet.wallet.TransferCompletedEvent;

/**
 * Events are delivered at least once, so a Redis marker per event id keeps the payee from being notified
 * twice. The marker is removed when sending fails, letting the Kafka retry (and eventually the DLT) take over.
 */
@Component
public class TransferNotificationListener {

    private static final Duration DEDUP_TTL = Duration.ofDays(7);

    private final NotificationClient notificationClient;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public TransferNotificationListener(NotificationClient notificationClient, StringRedisTemplate redis,
                                        ObjectMapper objectMapper) {
        this.notificationClient = notificationClient;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.TRANSFERS_COMPLETED, groupId = "notifications")
    public void onTransferCompleted(String payload, @Header(EventHeaders.EVENT_ID) String eventId)
            throws JsonProcessingException {
        var event = objectMapper.readValue(payload, TransferCompletedEvent.class);
        String marker = "notified:" + eventId;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(marker, "1", DEDUP_TTL))) {
            return;
        }
        boolean sent = notificationClient.send(event.payeeEmail(), "You received R$ %s from %s"
                .formatted(Money.fromCents(event.amountCents()).toPlainString(), event.payerName()));
        if (!sent) {
            redis.delete(marker);
            throw new IllegalStateException("Notification for event " + eventId + " was not delivered");
        }
    }
}
