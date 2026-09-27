package br.com.paywallet.fraud;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import br.com.paywallet.exception.FraudDeclinedException;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Real-time risk screening of every outflow, run before any money moves. Signals come from Redis (attempts in the
 * velocity window), the ledger (the customer's usual amounts) and the antifraud tables (known counterparties,
 * watchlist, blocked accounts). REVIEW lets the payment through and opens an alert for an analyst; DECLINE
 * refuses it.
 */
@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);
    private static final Duration HISTORY = Duration.ofDays(90);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final LedgerService ledger;
    private final UserService users;
    private final FraudProperties props;
    private final MeterRegistry meters;
    private final Clock clock;

    public FraudService(JdbcTemplate jdbc, StringRedisTemplate redis, LedgerService ledger, UserService users,
                        FraudProperties props, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.ledger = ledger;
        this.users = users;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
    }

    /** @throws FraudDeclinedException when the outflow is refused */
    public Assessment screen(FraudCheck check) {
        var assessment = assess(check);
        if (assessment.declined()) {
            throw new FraudDeclinedException();
        }
        return assessment;
    }

    public Assessment assess(FraudCheck check) {
        Instant now = clock.instant();
        var user = users.get(check.userId());
        var walletId = ledger.walletOf(check.userId()).getId();
        var history = jdbc.queryForMap("""
                SELECT count(*) AS n, coalesce(avg(amount), 0) AS average FROM postings
                 WHERE account_id = ? AND direction = 'DEBIT' AND created_at >= ?
                """, walletId, Timestamp.from(now.minus(HISTORY)));
        boolean card = check.channel() == Channel.CARD;
        var signals = new RiskEvaluator.Signals(
                check.amountCents(),
                exists("SELECT count(*) FROM fraud_blocked_users WHERE user_id = ?", check.userId()),
                check.counterparty() != null
                        && exists("SELECT count(*) FROM fraud_watchlist WHERE value = ?", check.counterparty()),
                check.counterparty() != null && exists(
                        "SELECT count(*) FROM fraud_counterparties WHERE user_id = ? AND counterparty = ?",
                        check.userId(), check.counterparty()),
                check.counterparty() != null,
                recordAttempt(check.userId(), now),
                ((Number) history.get("n")).longValue(),
                ((Number) history.get("average")).longValue(),
                Duration.between(user.getCreatedAt(), now).toDays(),
                now.atZone(props.zone()).toLocalTime(),
                card ? recentCardDeclines(check.userId(), now) : 0,
                card);
        var assessment = RiskEvaluator.evaluate(signals, props);
        meters.counter("paywallet.fraud.assessments", "channel", check.channel().name(), "decision",
                assessment.decision().name()).increment();
        assessment.rules().forEach(rule -> meters.counter("paywallet.fraud.rules", "channel", check.channel().name(),
                "rule", rule.name()).increment());

        if (!assessment.declined() && check.counterparty() != null && !signals.knownCounterparty()) {
            jdbc.update("""
                    INSERT INTO fraud_counterparties (user_id, counterparty, first_seen) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, check.userId(), check.counterparty(), Timestamp.from(now));
        }
        if (assessment.decision() != Assessment.Decision.APPROVE) {
            jdbc.update("""
                    INSERT INTO fraud_alerts (id, user_id, channel, amount, counterparty, score, decision, rules,
                                              status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                    """, UUID.randomUUID(), check.userId(), check.channel().name(), check.amountCents(),
                    check.counterparty(), assessment.score(), assessment.decision().name(),
                    assessment.rules().stream().map(Enum::name).collect(Collectors.joining(",")), Timestamp.from(now));
            log.info("Risk {} for user {} on {} (score {}, rules {})", assessment.decision(), check.userId(),
                    check.channel(), assessment.score(), assessment.rules());
        }
        return assessment;
    }

    /**
     * Counts outflow attempts, declined ones included, in a sliding window kept in a Redis sorted set. Fails open:
     * without Redis the velocity rule is skipped rather than blocking every payment.
     */
    private long recordAttempt(Long userId, Instant now) {
        String key = "fraud:velocity:" + userId;
        long windowStart = now.minus(props.velocityWindow()).toEpochMilli();
        try {
            var zset = redis.opsForZSet();
            zset.add(key, now.toEpochMilli() + ":" + UUID.randomUUID(), now.toEpochMilli());
            zset.removeRangeByScore(key, 0, windowStart);
            redis.expire(key, props.velocityWindow());
            Long count = zset.count(key, windowStart, Double.MAX_VALUE);
            return count == null ? 0 : count;
        } catch (DataAccessException e) {
            log.warn("Velocity check skipped for user {}: {}", userId, e.getMessage());
            return 0;
        }
    }

    private long recentCardDeclines(Long userId, Instant now) {
        Long declines = jdbc.queryForObject("""
                SELECT count(*) FROM card_authorizations a JOIN cards c ON c.id = a.card_id
                 WHERE c.user_id = ? AND a.status = 'DECLINED' AND a.created_at >= ?
                """, Long.class, userId, Timestamp.from(now.minus(props.cardDeclinesWindow())));
        return declines == null ? 0 : declines;
    }

    private boolean exists(String countQuery, Object... args) {
        Long count = jdbc.queryForObject(countQuery, Long.class, args);
        return count != null && count > 0;
    }
}
