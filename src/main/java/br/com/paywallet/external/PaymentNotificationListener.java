package br.com.paywallet.external;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.paywallet.credit.InstallmentOverdueEvent;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.merchant.ChargePaidEvent;
import br.com.paywallet.messaging.EventHeaders;
import br.com.paywallet.messaging.Topics;
import br.com.paywallet.pix.PixReceivedEvent;
import br.com.paywallet.wallet.TransferCompletedEvent;

/**
 * Events are delivered at least once, so a Redis marker per event id keeps the payee from being notified
 * twice. The marker is removed when sending fails, letting the Kafka retry (and eventually the DLT) take over.
 */
@Component
public class PaymentNotificationListener {

    private static final Duration DEDUP_TTL = Duration.ofDays(7);

    private final NotificationClient notificationClient;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PaymentNotificationListener(NotificationClient notificationClient, StringRedisTemplate redis,
                                       ObjectMapper objectMapper) {
        this.notificationClient = notificationClient;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.TRANSFERS_COMPLETED, groupId = "notifications")
    public void onTransferCompleted(String payload, @Header(EventHeaders.EVENT_ID) String eventId)
            throws JsonProcessingException {
        var event = objectMapper.readValue(payload, TransferCompletedEvent.class);
        notifyOnce(eventId, event.payeeEmail(), "You received R$ %s from %s"
                .formatted(Money.fromCents(event.amountCents()).toPlainString(), event.payerName()));
    }

    @KafkaListener(topics = Topics.PIX_RECEIVED, groupId = "notifications")
    public void onPixReceived(String payload, @Header(EventHeaders.EVENT_ID) String eventId)
            throws JsonProcessingException {
        var event = objectMapper.readValue(payload, PixReceivedEvent.class);
        notifyOnce(eventId, event.payeeEmail(), "You received a Pix of R$ %s from %s"
                .formatted(Money.fromCents(event.amountCents()).toPlainString(), event.payerName()));
    }

    @KafkaListener(topics = Topics.CHARGES_PAID, groupId = "notifications")
    public void onChargePaid(String payload, @Header(EventHeaders.EVENT_ID) String eventId)
            throws JsonProcessingException {
        var event = objectMapper.readValue(payload, ChargePaidEvent.class);
        String label = event.reference() != null ? event.reference() : event.chargeId().toString();
        notifyOnce(eventId, event.merchantEmail(), "Charge %s paid: R$ %s (R$ %s net)".formatted(label,
                Money.fromCents(event.amountCents()).toPlainString(), Money.fromCents(event.netCents()).toPlainString()));
    }

    @KafkaListener(topics = Topics.LOAN_INSTALLMENT_OVERDUE, groupId = "notifications")
    public void onInstallmentOverdue(String payload, @Header(EventHeaders.EVENT_ID) String eventId)
            throws JsonProcessingException {
        var event = objectMapper.readValue(payload, InstallmentOverdueEvent.class);
        notifyOnce(eventId, event.email(), ("Installment %d of your loan (R$ %s, due %s) is overdue. Keep a balance "
                + "in your wallet so it can be debited.").formatted(event.number(),
                Money.fromCents(event.amountCents()).toPlainString(), event.dueDate()));
    }

    private void notifyOnce(String eventId, String email, String message) {
        String marker = "notified:" + eventId;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(marker, "1", DEDUP_TTL))) {
            return;
        }
        if (!notificationClient.send(email, message)) {
            redis.delete(marker);
            throw new IllegalStateException("Notification for event " + eventId + " was not delivered");
        }
    }
}
