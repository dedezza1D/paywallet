package br.com.paywallet.fraud;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.fraud.FraudDtos.AlertResponse;
import br.com.paywallet.fraud.FraudDtos.AlertStatus;
import br.com.paywallet.fraud.FraudDtos.BlockedUser;
import br.com.paywallet.fraud.FraudDtos.ResolveAlertRequest;
import br.com.paywallet.fraud.FraudDtos.WatchlistEntry;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.user.UserService;

/** Analyst tools: the alert queue, account blocks and the counterparty watchlist. */
@Service
public class FraudAdminService {

    private static final String ALERT_COLUMNS = "id, user_id, channel, amount, counterparty, score, decision, rules, "
            + "status, resolved_by, resolution_note, created_at, resolved_at";

    private final JdbcTemplate jdbc;
    private final UserService users;
    private final Clock clock;

    public FraudAdminService(JdbcTemplate jdbc, UserService users, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.clock = clock;
    }

    public List<AlertResponse> alerts(AlertStatus status, Long userId) {
        String statusName = status == null ? null : status.name();
        return jdbc.query("SELECT " + ALERT_COLUMNS + " FROM fraud_alerts"
                        + " WHERE (CAST(? AS VARCHAR) IS NULL OR status = ?) AND (CAST(? AS BIGINT) IS NULL OR user_id = ?)"
                        + " ORDER BY created_at DESC LIMIT 200",
                FraudAdminService::alert, statusName, statusName, userId, userId);
    }

    @Transactional
    public AlertResponse resolve(UUID alertId, ResolveAlertRequest req, Long analystId) {
        if (req.status() == AlertStatus.OPEN) {
            throw new BusinessException("Resolve an alert as DISMISSED or CONFIRMED");
        }
        var alert = jdbc.query("SELECT " + ALERT_COLUMNS + " FROM fraud_alerts WHERE id = ? FOR UPDATE",
                        FraudAdminService::alert, alertId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Alert not found"));
        if (alert.status() != AlertStatus.OPEN) {
            throw new BusinessException("Alert already resolved as " + alert.status());
        }
        Instant now = clock.instant();
        jdbc.update("UPDATE fraud_alerts SET status = ?, resolved_by = ?, resolution_note = ?, resolved_at = ? WHERE id = ?",
                req.status().name(), analystId, req.note(), Timestamp.from(now), alertId);
        if (req.status() == AlertStatus.CONFIRMED) {
            String reason = req.note() != null ? req.note() : "Confirmed fraud on alert " + alertId;
            if (!Boolean.FALSE.equals(req.blockUser())) {
                jdbc.update("""
                        INSERT INTO fraud_blocked_users (user_id, reason, alert_id, blocked_by, blocked_at)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id) DO NOTHING
                        """, alert.userId(), reason, alertId, analystId, Timestamp.from(now));
            }
            if (Boolean.TRUE.equals(req.watchlistCounterparty()) && alert.counterparty() != null) {
                jdbc.update("""
                        INSERT INTO fraud_watchlist (value, reason, alert_id, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT (value) DO NOTHING
                        """, alert.counterparty(), reason, alertId, analystId, Timestamp.from(now));
            }
        }
        return jdbc.queryForObject("SELECT " + ALERT_COLUMNS + " FROM fraud_alerts WHERE id = ?",
                FraudAdminService::alert, alertId);
    }

    public BlockedUser block(Long userId, String reason, Long analystId) {
        users.get(userId);
        jdbc.update("""
                INSERT INTO fraud_blocked_users (user_id, reason, blocked_by, blocked_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, userId, reason, analystId, Timestamp.from(clock.instant()));
        return blocked(userId);
    }

    public void unblock(Long userId) {
        if (jdbc.update("DELETE FROM fraud_blocked_users WHERE user_id = ?", userId) == 0) {
            throw new NotFoundException("User is not blocked");
        }
    }

    public BlockedUser blocked(Long userId) {
        return jdbc.query("""
                        SELECT user_id, reason, alert_id, blocked_by, blocked_at FROM fraud_blocked_users WHERE user_id = ?
                        """, (rs, i) -> new BlockedUser(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class),
                        (Long) rs.getObject(4), rs.getTimestamp(5).toInstant()), userId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("User is not blocked"));
    }

    public List<WatchlistEntry> watchlist() {
        return jdbc.query("SELECT value, reason FROM fraud_watchlist ORDER BY created_at DESC",
                (rs, i) -> new WatchlistEntry(rs.getString(1), rs.getString(2)));
    }

    public WatchlistEntry addToWatchlist(WatchlistEntry entry, Long analystId) {
        jdbc.update("""
                INSERT INTO fraud_watchlist (value, reason, created_by, created_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (value) DO UPDATE SET reason = EXCLUDED.reason
                """, entry.value(), entry.reason(), analystId, Timestamp.from(clock.instant()));
        return entry;
    }

    public void removeFromWatchlist(String value) {
        if (jdbc.update("DELETE FROM fraud_watchlist WHERE value = ?", value) == 0) {
            throw new NotFoundException("Value is not on the watchlist");
        }
    }

    private static AlertResponse alert(ResultSet rs, int row) throws SQLException {
        return new AlertResponse(rs.getObject("id", UUID.class), rs.getLong("user_id"),
                Channel.valueOf(rs.getString("channel")), Money.fromCents(rs.getLong("amount")),
                rs.getString("counterparty"), rs.getInt("score"), Assessment.Decision.valueOf(rs.getString("decision")),
                Arrays.stream(rs.getString("rules").split(",")).map(RiskRule::valueOf).toList(),
                AlertStatus.valueOf(rs.getString("status")), (Long) rs.getObject("resolved_by"),
                rs.getString("resolution_note"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant());
    }
}
