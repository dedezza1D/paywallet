package br.com.paywallet.card;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import br.com.paywallet.card.CardDtos.ChargeResponse;
import br.com.paywallet.ledger.Money;

/** Credit card lines and the limit they consume. Amounts are in cents. */
@Component
class CardCharges {

    private final JdbcTemplate jdbc;

    CardCharges(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void add(UUID cardId, String authorizationId, String description, long amount, int installment, int installments,
             LocalDate billingDate, Instant now) {
        jdbc.update("""
                INSERT INTO card_charges (id, card_id, authorization_id, description, amount, installment, installments,
                                          billing_date, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), cardId, authorizationId, description, amount, installment, installments,
                Date.valueOf(billingDate), Timestamp.from(now));
    }

    /** Splits a purchase into monthly installments; the first one absorbs the rounding remainder. */
    void addInstallments(UUID cardId, String authorizationId, String merchantName, long amount, int installments,
                         LocalDate firstBillingDate, Instant now) {
        long base = amount / installments;
        for (int k = 1; k <= installments; k++) {
            long value = k == 1 ? amount - base * (installments - 1) : base;
            add(cardId, authorizationId, merchantName, value, k, installments, firstBillingDate.plusMonths(k - 1), now);
        }
    }

    long unbilledUpTo(UUID cardId, LocalDate date) {
        Long total = jdbc.queryForObject("""
                SELECT coalesce(sum(amount), 0) FROM card_charges
                 WHERE card_id = ? AND statement_id IS NULL AND billing_date <= ?
                """, Long.class, cardId, Date.valueOf(date));
        return total == null ? 0 : total;
    }

    void assignUpTo(UUID cardId, LocalDate date, UUID statementId) {
        jdbc.update("""
                UPDATE card_charges SET statement_id = ?
                 WHERE card_id = ? AND statement_id IS NULL AND billing_date <= ?
                """, statementId, cardId, Date.valueOf(date));
    }

    List<ChargeResponse> ofStatement(UUID statementId) {
        return jdbc.query("""
                SELECT description, amount, installment, installments, billing_date FROM card_charges
                 WHERE statement_id = ? ORDER BY billing_date, created_at, installment
                """, (rs, i) -> new ChargeResponse(rs.getString(1), Money.fromCents(rs.getLong(2)), rs.getInt(3),
                rs.getInt(4), rs.getObject(5, LocalDate.class)), statementId);
    }

    /**
     * Limit in use: approved purchases not yet cleared, charges not yet billed (including future installments)
     * and what is still owed on open statements.
     */
    long used(UUID cardId) {
        Long used = jdbc.queryForObject("""
                SELECT coalesce((SELECT sum(amount) FROM card_authorizations WHERE card_id = ? AND status = 'APPROVED'), 0)
                     + coalesce((SELECT sum(amount) FROM card_charges WHERE card_id = ? AND statement_id IS NULL), 0)
                     + coalesce((SELECT sum(total - paid) FROM card_statements WHERE card_id = ? AND status = 'OPEN'), 0)
                """, Long.class, cardId, cardId, cardId);
        return used == null ? 0 : used;
    }
}
