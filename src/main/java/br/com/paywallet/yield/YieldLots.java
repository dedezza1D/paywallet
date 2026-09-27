package br.com.paywallet.yield;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.yield.YieldTaxes.Lot;

/**
 * Keeps each wallet's balance split by entry date. New postings since the last run are replayed in order: money in
 * opens or grows the lot of its local date, money out consumes the oldest lots first. Credited yield enters as a
 * new lot, a simplification that slightly overstates taxes on yield earned by yield.
 */
@Component
class YieldLots {

    private final JdbcTemplate jdbc;
    private final YieldProperties props;

    YieldLots(JdbcTemplate jdbc, YieldProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Brings the lots up to {@code cutoff} and returns them, oldest first. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Lot> advance(UUID accountId, Instant cutoff) {
        var processed = jdbc.query(
                "SELECT processed_until FROM yield_lot_cursors WHERE account_id = ? FOR UPDATE",
                (rs, i) -> rs.getTimestamp(1).toInstant(), accountId).stream().findFirst().orElse(Instant.EPOCH);
        Map<LocalDate, Long> lots = new TreeMap<>();
        jdbc.query("SELECT lot_date, amount FROM yield_lots WHERE account_id = ?",
                rs -> {
                    lots.put(rs.getObject(1, LocalDate.class), rs.getLong(2));
                }, accountId);
        if (!processed.isBefore(cutoff)) {
            return toList(lots);
        }

        jdbc.query("""
                SELECT direction, amount, created_at FROM postings
                 WHERE account_id = ? AND created_at > ? AND created_at < ?
                 ORDER BY created_at, id
                """, rs -> {
            long amount = rs.getLong(2);
            if ("CREDIT".equals(rs.getString(1))) {
                lots.merge(rs.getTimestamp(3).toInstant().atZone(props.zone()).toLocalDate(), amount, Long::sum);
            } else {
                consumeOldest(lots, amount);
            }
        }, accountId, Timestamp.from(processed), Timestamp.from(cutoff));

        jdbc.update("DELETE FROM yield_lots WHERE account_id = ?", accountId);
        lots.forEach((date, amount) -> jdbc.update(
                "INSERT INTO yield_lots (account_id, lot_date, amount) VALUES (?, ?, ?)", accountId, date, amount));
        jdbc.update("""
                INSERT INTO yield_lot_cursors (account_id, processed_until) VALUES (?, ?)
                ON CONFLICT (account_id) DO UPDATE SET processed_until = EXCLUDED.processed_until
                """, accountId, Timestamp.from(cutoff));
        return toList(lots);
    }

    private static void consumeOldest(Map<LocalDate, Long> lots, long amount) {
        Iterator<Map.Entry<LocalDate, Long>> it = lots.entrySet().iterator();
        long left = amount;
        while (left > 0 && it.hasNext()) {
            var lot = it.next();
            long used = Math.min(left, lot.getValue());
            left -= used;
            if (used == lot.getValue()) {
                it.remove();
            } else {
                lot.setValue(lot.getValue() - used);
            }
        }
    }

    private static List<Lot> toList(Map<LocalDate, Long> lots) {
        List<Lot> list = new ArrayList<>();
        lots.forEach((date, amount) -> list.add(new Lot(date, amount)));
        return list;
    }
}
