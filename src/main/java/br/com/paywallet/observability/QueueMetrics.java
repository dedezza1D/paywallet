package br.com.paywallet.observability;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Size and age of the work waiting for a worker, a partner or an analyst, read from the database on each scrape. A
 * queue that keeps growing or ageing means a worker is stuck or a partner is down.
 */
@Component
class QueueMetrics {

    private record Queue(String name, String table, String pendingCondition) {
    }

    private static final List<Queue> QUEUES = List.of(
            new Queue("outbox", "outbox_events", "published_at IS NULL"),
            new Queue("pix", "pix_payments", "status = 'PENDING'"),
            new Queue("pix_returns", "pix_returns", "status = 'PENDING'"),
            new Queue("bills", "bill_payments", "status = 'PENDING'"),
            new Queue("marketplace", "marketplace_orders", "status = 'PENDING'"),
            new Queue("card_clearing", "card_authorizations", "status = 'APPROVED'"),
            new Queue("pix_fraud_claims", "pix_fraud_claims", "status = 'OPEN'"),
            new Queue("fraud_alerts", "fraud_alerts", "status = 'OPEN'"));

    private final JdbcTemplate jdbc;

    QueueMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (Queue queue : QUEUES) {
            Gauge.builder("paywallet.queue.pending",
                            () -> query("SELECT count(*) FROM %s WHERE %s".formatted(queue.table(), queue.pendingCondition())))
                    .description("Items waiting to be processed")
                    .tag("queue", queue.name())
                    .register(registry);
            Gauge.builder("paywallet.queue.oldest.age", () -> query(
                            "SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM %s WHERE %s"
                                    .formatted(queue.table(), queue.pendingCondition())))
                    .description("Age of the oldest waiting item")
                    .baseUnit("seconds")
                    .tag("queue", queue.name())
                    .register(registry);
        }
    }

    private double query(String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (DataAccessException e) {
            return Double.NaN;
        }
    }
}
