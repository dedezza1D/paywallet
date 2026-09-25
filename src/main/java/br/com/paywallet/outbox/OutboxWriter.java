package br.com.paywallet.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** MANDATORY: an event written outside the business transaction would defeat the outbox. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String key, String eventType, Object payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, topic, message_key, event_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, id, topic, key, eventType, toJson(payload), Timestamp.from(clock.instant()));
        return id;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event payload is not serializable", e);
        }
    }
}
