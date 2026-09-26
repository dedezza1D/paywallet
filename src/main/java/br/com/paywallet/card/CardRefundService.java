package br.com.paywallet.card;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.card.CardDtos.DisputeOutcomeRequest;
import br.com.paywallet.card.CardDtos.DisputeReason;
import br.com.paywallet.card.CardDtos.DisputeRequest;
import br.com.paywallet.card.CardDtos.DisputeResponse;
import br.com.paywallet.card.CardDtos.DisputeStatus;
import br.com.paywallet.card.CardDtos.RefundRequest;
import br.com.paywallet.card.CardDtos.RefundResponse;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.ConflictException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;

/**
 * Money coming back on cleared card purchases: merchant refunds reported by the processor, and chargebacks won in a
 * dispute the customer opened. Debit card money returns to the wallet; credit card money becomes a negative charge
 * that lowers the next statement. Together they never exceed the cleared amount.
 */
@Service
public class CardRefundService {

    private static final String DISPUTE_COLUMNS =
            "id, authorization_id, reason, description, amount, status, created_at, resolved_at";

    private final CardRepository cards;
    private final CardAuthorizationRepository authorizations;
    private final CardService cardService;
    private final CardCharges charges;
    private final CardProcessor processor;
    private final LedgerService ledger;
    private final BalanceCache balanceCache;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final CardProperties props;
    private final Clock clock;

    public CardRefundService(CardRepository cards, CardAuthorizationRepository authorizations, CardService cardService,
                             CardCharges charges, CardProcessor processor, LedgerService ledger,
                             BalanceCache balanceCache, TransactionTemplate transactions, JdbcTemplate jdbc,
                             CardProperties props, Clock clock) {
        this.cards = cards;
        this.authorizations = authorizations;
        this.cardService = cardService;
        this.charges = charges;
        this.processor = processor;
        this.ledger = ledger;
        this.balanceCache = balanceCache;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** Idempotent by the processor's refund id. */
    public RefundResponse refund(RefundRequest req) {
        long amount = Money.toCents(req.amount());
        var existing = findRefund(req.refundId());
        if (existing != null) {
            return sameRefund(existing, req.authorizationId(), amount);
        }
        try {
            return transactions.execute(status -> {
                var auth = lockCleared(req.authorizationId());
                return credit(auth, amount, "REFUND", req.refundId());
            });
        } catch (DuplicateKeyException e) {
            return sameRefund(findRefund(req.refundId()), req.authorizationId(), amount);
        }
    }

    public DisputeResponse openDispute(Long userId, UUID cardId, String authorizationId, DisputeRequest req) {
        var card = cardService.owned(userId, cardId);
        UUID id = UUID.randomUUID();
        try {
            transactions.executeWithoutResult(status -> {
                var auth = lockCleared(authorizationId);
                if (!auth.getCardId().equals(card.getId())) {
                    throw new NotFoundException("Transaction not found");
                }
                long disputable = remaining(auth);
                if (disputable <= 0) {
                    throw new BusinessException("This purchase has already been refunded");
                }
                jdbc.update("""
                        INSERT INTO card_disputes (id, authorization_id, reason, description, amount, status,
                                                   created_at)
                        VALUES (?, ?, ?, ?, ?, 'OPEN', ?)
                        """, id, authorizationId, req.reason().name(), req.description(), disputable,
                        Timestamp.from(clock.instant()));
                processor.openDispute(card.getProcessorToken(), authorizationId, disputable, req.reason().name());
            });
        } catch (DuplicateKeyException e) {
            throw new ConflictException("This purchase is already disputed");
        }
        return dispute(authorizationId);
    }

    public List<DisputeResponse> disputes(Long userId, UUID cardId) {
        cardService.owned(userId, cardId);
        return jdbc.query("""
                SELECT d.id, d.authorization_id, d.reason, d.description, d.amount, d.status, d.created_at,
                       d.resolved_at
                  FROM card_disputes d JOIN card_authorizations a ON a.id = d.authorization_id
                 WHERE a.card_id = ? ORDER BY d.created_at DESC
                """, CardRefundService::dispute, cardId);
    }

    /** Idempotent: a dispute is resolved once and repeated outcomes are ignored. */
    public DisputeResponse resolveDispute(DisputeOutcomeRequest req) {
        if (req.outcome() == DisputeStatus.OPEN) {
            throw new BusinessException("Outcome must be WON or LOST");
        }
        transactions.executeWithoutResult(status -> {
            var auth = authorizations.lockById(req.authorizationId())
                    .orElseThrow(() -> new NotFoundException("Authorization not found"));
            var dispute = dispute(auth.getId());
            if (dispute.status() != DisputeStatus.OPEN) {
                return;
            }
            if (req.outcome() == DisputeStatus.WON) {
                long amount = Math.min(Money.toCents(dispute.amount()), remaining(auth));
                if (amount > 0) {
                    credit(auth, amount, "CHARGEBACK", "chargeback-" + dispute.id());
                }
            }
            jdbc.update("UPDATE card_disputes SET status = ?, resolved_at = ? WHERE id = ?", req.outcome().name(),
                    Timestamp.from(clock.instant()), dispute.id());
        });
        return dispute(req.authorizationId());
    }

    /** Must run inside a transaction holding the authorization lock. */
    private RefundResponse credit(CardAuthorization auth, long amount, String kind, String id) {
        if (amount > remaining(auth)) {
            throw new BusinessException("Amount exceeds what is left of the purchase (R$ %s)"
                    .formatted(Money.fromCents(remaining(auth))));
        }
        var card = cards.lockById(auth.getCardId()).orElseThrow();
        Instant now = clock.instant();
        boolean chargeback = "CHARGEBACK".equals(kind);
        var type = chargeback ? LedgerTransactionType.CARD_CHARGEBACK : LedgerTransactionType.CARD_REFUND;
        String description = (chargeback ? "Chargeback: " : "Refund: ") + auth.getMerchantName();
        jdbc.update("INSERT INTO card_refunds (id, authorization_id, kind, amount, created_at) VALUES (?, ?, ?, ?, ?)",
                id, auth.getId(), kind, amount, Timestamp.from(now));
        if (card.getType() == Card.Type.DEBIT) {
            ledger.post(new PostCommand(type, "card-refund:" + id, description,
                    List.of(Leg.debit(AccountType.CARD_SETTLEMENT_ACCOUNT_ID, amount),
                            Leg.credit(ledger.walletOf(card.getUserId()).getId(), amount))));
            evictAfterCommit(card.getUserId());
        } else {
            ledger.post(new PostCommand(type, "card-refund:" + id, description,
                    List.of(Leg.debit(AccountType.CARD_SETTLEMENT_ACCOUNT_ID, amount),
                            Leg.credit(AccountType.CARD_RECEIVABLES_ACCOUNT_ID, amount))));
            charges.add(card.getId(), auth.getId(), description, -amount, 1, 1,
                    LocalDate.now(clock.withZone(props.zone())), now);
        }
        return new RefundResponse(id, auth.getId(), kind, Money.fromCents(amount), now);
    }

    private void evictAfterCommit(Long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                balanceCache.evict(userId);
            }
        });
    }

    private CardAuthorization lockCleared(String authorizationId) {
        var auth = authorizations.lockById(authorizationId)
                .orElseThrow(() -> new NotFoundException("Authorization not found"));
        if (auth.getStatus() != CardAuthorization.Status.CLEARED) {
            throw new ConflictException("Only cleared purchases can be refunded or disputed");
        }
        return auth;
    }

    private long remaining(CardAuthorization auth) {
        Long refunded = jdbc.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM card_refunds WHERE authorization_id = ?", Long.class,
                auth.getId());
        return auth.getClearedAmount() - (refunded == null ? 0 : refunded);
    }

    private RefundResponse findRefund(String id) {
        return jdbc.query("SELECT id, authorization_id, kind, amount, created_at FROM card_refunds WHERE id = ?",
                (rs, i) -> new RefundResponse(rs.getString(1), rs.getString(2), rs.getString(3),
                        Money.fromCents(rs.getLong(4)), rs.getTimestamp(5).toInstant()), id)
                .stream().findFirst().orElse(null);
    }

    private static RefundResponse sameRefund(RefundResponse existing, String authorizationId, long amount) {
        if (!existing.authorizationId().equals(authorizationId) || Money.toCents(existing.amount()) != amount) {
            throw new ConflictException("Refund id already used for a different refund");
        }
        return existing;
    }

    private DisputeResponse dispute(String authorizationId) {
        return jdbc.query("SELECT " + DISPUTE_COLUMNS + " FROM card_disputes WHERE authorization_id = ?",
                CardRefundService::dispute, authorizationId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Dispute not found"));
    }

    private static DisputeResponse dispute(ResultSet rs, int row) throws SQLException {
        return new DisputeResponse(rs.getObject("id", UUID.class), rs.getString("authorization_id"),
                DisputeReason.valueOf(rs.getString("reason")), rs.getString("description"),
                Money.fromCents(rs.getLong("amount")), DisputeStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant());
    }
}
