package br.com.paywallet.pix;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.merchant.ChargeService;
import br.com.paywallet.pix.PixReturnDtos.PixReturnResponse;
import br.com.paywallet.pix.PixReturnDtos.ReturnResult;
import jakarta.persistence.EntityManager;

/**
 * Returns (devoluções) of received Pix, total or partial, up to 90 days after the payment. A return between two
 * customers of this institution settles at once; a return to another institution debits the wallet into the Pix
 * settlement account and stays PENDING until {@link PixReturnWorker} submits it. Returns are not screened by the
 * risk engine: sending money back to where it came from is how fraud gets undone, even from a frozen account.
 */
@Service
public class PixReturnService {

    static final Duration RETURN_WINDOW = Duration.ofDays(90);

    private final PixPaymentRepository payments;
    private final PixReturnRepository returns;
    private final LedgerService ledger;
    private final ChargeService charges;
    private final BalanceCache balanceCache;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final PixProperties props;
    private final Clock clock;

    public PixReturnService(PixPaymentRepository payments, PixReturnRepository returns, LedgerService ledger,
                            ChargeService charges, BalanceCache balanceCache, TransactionTemplate transactions,
                            JdbcTemplate jdbc, EntityManager em, PixProperties props, Clock clock) {
        this.payments = payments;
        this.returns = returns;
        this.ledger = ledger;
        this.charges = charges;
        this.balanceCache = balanceCache;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.em = em;
        this.props = props;
        this.clock = clock;
    }

    /** @param value in cents; null returns everything not yet returned */
    public ReturnResult request(Long receiverId, String endToEndId, Long value, PixReturn.Reason reason,
                                String idempotencyKey) {
        String key = "pix-return:%d:%s".formatted(receiverId, idempotencyKey);
        var previous = returns.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            return replay(previous.get(), endToEndId, value);
        }
        PixReturn created;
        try {
            created = transactions.execute(status -> {
                var original = payments.lockByEndToEndId(endToEndId)
                        .filter(p -> receiverId.equals(p.getPayeeUserId()))
                        .orElseThrow(() -> new NotFoundException("Pix not found"));
                if (original.getStatus() != PixPayment.Status.COMPLETED) {
                    throw new BusinessException("Only completed Pix can be returned");
                }
                if (original.getCreatedAt().plus(RETURN_WINDOW).isBefore(clock.instant())) {
                    throw new BusinessException("Pix can only be returned within 90 days");
                }
                if (underFraudAnalysis(endToEndId)) {
                    throw new BusinessException("This Pix is under fraud analysis");
                }
                long remaining = original.getAmount() - returns.returnedAmount(endToEndId);
                long amount = value == null ? remaining : value;
                if (amount <= 0 || amount > remaining) {
                    throw new BusinessException("Amount exceeds what can still be returned (R$ %s)"
                            .formatted(Money.fromCents(remaining)));
                }
                return create(original, receiverId, amount, reason == null ? PixReturn.Reason.MD06 : reason, key);
            });
        } catch (DataIntegrityViolationException e) {
            return returns.findByIdempotencyKey(key).map(r -> replay(r, endToEndId, value)).orElseThrow(() -> e);
        }
        balanceCache.evict(receiverId);
        var original = payments.findByEndToEndId(endToEndId).orElseThrow();
        if (original.getPayerUserId() != null) {
            balanceCache.evict(original.getPayerUserId());
        }
        return new ReturnResult(PixReturnResponse.from(created), false);
    }

    @Transactional(readOnly = true)
    public List<PixReturnResponse> list(Long userId, String endToEndId) {
        var original = payments.findByEndToEndId(endToEndId).filter(p -> p.involves(userId))
                .orElseThrow(() -> new NotFoundException("Pix not found"));
        return returns.findByOriginalEndToEndIdOrderByCreatedAt(original.getEndToEndId()).stream()
                .map(PixReturnResponse::from).toList();
    }

    /** Must run inside a transaction holding the lock on the original payment. */
    private PixReturn create(PixPayment original, Long receiverId, long amount, PixReturn.Reason reason, String key) {
        Instant now = clock.instant();
        String returnId = EndToEndIds.generateReturn(props.ispb(), now);
        var receiverWallet = ledger.walletOf(receiverId).getId();
        var destination = original.getScope() == PixPayment.Scope.INTERNAL
                ? ledger.walletOf(original.getPayerUserId()).getId() : AccountType.PIX_SETTLEMENT_ACCOUNT_ID;
        var tx = ledger.post(new PostCommand(LedgerTransactionType.PIX_RETURN, key, "Pix return " + returnId,
                List.of(Leg.debit(receiverWallet, amount), Leg.credit(destination, amount))));
        var created = new PixReturn(returnId, original, receiverId, amount, reason, key, tx.getId(), now);
        em.persist(created);
        charges.recordPixReturn(original.getEndToEndId(), amount);
        return created;
    }

    /**
     * Records money already moved back to the payer by an accepted fraud claim, so it counts against what can still
     * be returned. Runs inside the claim resolution transaction.
     */
    void recordFraudReturn(PixPayment original, Long analystId, long amount, UUID ledgerTransactionId,
                           String key) {
        Instant now = clock.instant();
        var created = new PixReturn(EndToEndIds.generateReturn(props.ispb(), now), original, analystId, amount,
                PixReturn.Reason.FR01, key, ledgerTransactionId, now);
        em.persist(created);
        charges.recordPixReturn(original.getEndToEndId(), amount);
    }

    private boolean underFraudAnalysis(String endToEndId) {
        Long open = jdbc.queryForObject(
                "SELECT count(*) FROM pix_fraud_claims WHERE end_to_end_id = ? AND status = 'OPEN'", Long.class,
                endToEndId);
        return open != null && open > 0;
    }

    private ReturnResult replay(PixReturn previous, String endToEndId, Long value) {
        if (!previous.getOriginalEndToEndId().equals(endToEndId)
                || (value != null && previous.getAmount() != value)) {
            throw new BusinessException("Idempotency-Key already used for a different return");
        }
        return new ReturnResult(PixReturnResponse.from(previous), true);
    }
}
