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

import io.micrometer.tracing.Tracer;

@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Tracer tracer, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.clock = clock;
    }

    /** MANDATORY: an event written outside the business transaction would defeat the outbox. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String key, String eventType, Object payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, topic, message_key, event_type, payload, created_at, trace_parent)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, id, topic, key, eventType, toJson(payload), Timestamp.from(clock.instant()), traceParent());
        return id;
    }

    private String traceParent() {
        var span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        var context = span.context();
        return "00-%s-%s-%s".formatted(context.traceId(), context.spanId(),
                Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event payload is not serializable", e);
        }
    }
}
