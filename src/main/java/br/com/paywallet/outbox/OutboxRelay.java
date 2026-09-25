package br.com.paywallet.outbox;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.messaging.EventHeaders;

/**
 * Publishes pending outbox events to Kafka in creation order. Delivery is at-least-once: if the broker
 * acknowledges but the commit fails, the event is sent again, so consumers must be idempotent.
 * FOR UPDATE SKIP LOCKED lets several instances relay concurrently without picking the same rows.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties props;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                       OutboxProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.props = props;
        this.clock = clock;
    }

    private record PendingEvent(UUID id, String topic, String key, String eventType, String payload) {
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval}")
    public void relay() {
        Integer published;
        do {
            published = tx.execute(status -> relayBatch());
        } while (published != null && published == props.batchSize());
    }

    /** @return events published in this batch; stops at the first failure to preserve ordering */
    private int relayBatch() {
        List<PendingEvent> batch = jdbc.query("""
                SELECT id, topic, message_key, event_type, payload::text
                  FROM outbox_events
                 WHERE published_at IS NULL
                 ORDER BY created_at
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """,
                (rs, i) -> new PendingEvent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)),
                props.batchSize());

        int published = 0;
        for (PendingEvent event : batch) {
            try {
                send(event);
            } catch (Exception e) {
                String error = e.getClass().getSimpleName() + ": " + e.getMessage();
                jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                        error.length() > 1000 ? error.substring(0, 1000) : error, event.id());
                log.warn("Outbox event {} not published, will retry: {}", event.id(), error);
                break;
            }
            jdbc.update("UPDATE outbox_events SET published_at = ?, attempts = attempts + 1 WHERE id = ?",
                    Timestamp.from(clock.instant()), event.id());
            published++;
        }
        return published;
    }

    private void send(PendingEvent event) throws Exception {
        var record = new ProducerRecord<>(event.topic(), event.key(), event.payload());
        record.headers().add(EventHeaders.EVENT_ID, event.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Scheduled(cron = "0 0 * * * *")
    public void deletePublished() {
        int deleted = jdbc.update("DELETE FROM outbox_events WHERE published_at < ?",
                Timestamp.from(clock.instant().minus(props.retention())));
        if (deleted > 0) {
            log.info("Deleted {} published outbox events", deleted);
        }
    }
}
