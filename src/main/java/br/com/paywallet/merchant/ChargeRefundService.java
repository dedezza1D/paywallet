package br.com.paywallet.merchant;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.merchant.ChargeDtos.ChargeResponse;
import br.com.paywallet.pix.PixReturn;
import br.com.paywallet.pix.PixReturnService;

/**
 * Refunds of paid charges, total or partial, from the merchant's wallet. The MDR is not given back: like card
 * acquirers, the platform keeps the fee of a refunded sale. Charges paid by wallet are refunded straight to the
 * payer's wallet; charges paid by Pix become a Pix return, which also reaches payers at other institutions.
 */
@Service
public class ChargeRefundService {

    public record RefundResult(ChargeResponse charge, boolean replayed) {
    }

    private record PreviousRefund(UUID chargeId, long amount) {
    }

    private final ChargeRepository charges;
    private final ChargeService chargeService;
    private final PixReturnService pixReturns;
    private final LedgerService ledger;
    private final BalanceCache balanceCache;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ChargeRefundService(ChargeRepository charges, ChargeService chargeService, PixReturnService pixReturns,
                               LedgerService ledger, BalanceCache balanceCache, TransactionTemplate transactions,
                               JdbcTemplate jdbc, Clock clock) {
        this.charges = charges;
        this.chargeService = chargeService;
        this.pixReturns = pixReturns;
        this.ledger = ledger;
        this.balanceCache = balanceCache;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @param value in cents; null refunds everything not yet refunded */
    public RefundResult refund(Long merchantId, UUID chargeId, Long value, String idempotencyKey) {
        var charge = charges.findByIdAndMerchantId(chargeId, merchantId)
                .orElseThrow(() -> new NotFoundException("Charge not found"));
        if (charge.getStatus() != Charge.Status.PAID) {
            throw new BusinessException("Only paid charges can be refunded");
        }
        if (charge.getPaymentMethod() == PaymentMethod.PIX) {
            var result = pixReturns.request(merchantId, charge.getEndToEndId(), value, PixReturn.Reason.MD06,
                    "charge-" + idempotencyKey);
            return new RefundResult(chargeService.get(merchantId, chargeId), result.replayed());
        }

        String key = "charge-refund:%d:%s".formatted(merchantId, idempotencyKey);
        if (isReplay(key, chargeId, value)) {
            return new RefundResult(chargeService.get(merchantId, chargeId), true);
        }
        try {
            transactions.executeWithoutResult(status -> {
                var locked = charges.lockById(chargeId).orElseThrow();
                long remaining = locked.refundable();
                long amount = value == null ? remaining : value;
                if (amount <= 0 || amount > remaining) {
                    throw new BusinessException("Amount exceeds what can still be refunded (R$ %s)"
                            .formatted(Money.fromCents(remaining)));
                }
                var tx = ledger.post(new PostCommand(LedgerTransactionType.CHARGE_REFUND, key,
                        "Refund of charge " + chargeId,
                        List.of(Leg.debit(ledger.walletOf(merchantId).getId(), amount),
                                Leg.credit(ledger.walletOf(locked.getPayerUserId()).getId(), amount))));
                jdbc.update("""
                        INSERT INTO charge_refunds (id, charge_id, amount, idempotency_key, ledger_transaction_id,
                                                    created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), chargeId, amount, key, tx.getId(), Timestamp.from(clock.instant()));
                locked.addRefund(amount);
            });
        } catch (DataIntegrityViolationException e) {
            if (isReplay(key, chargeId, value)) {
                return new RefundResult(chargeService.get(merchantId, chargeId), true);
            }
            throw e;
        }
        balanceCache.evict(merchantId);
        balanceCache.evict(charge.getPayerUserId());
        return new RefundResult(chargeService.get(merchantId, chargeId), false);
    }

    private boolean isReplay(String key, UUID chargeId, Long value) {
        var previous = jdbc.query("SELECT charge_id, amount FROM charge_refunds WHERE idempotency_key = ?",
                (rs, i) -> new PreviousRefund(rs.getObject(1, UUID.class), rs.getLong(2)), key);
        if (previous.isEmpty()) {
            return false;
        }
        var row = previous.getFirst();
        if (!chargeId.equals(row.chargeId()) || (value != null && value != row.amount())) {
            throw new BusinessException("Idempotency-Key already used for a different refund");
        }
        return true;
    }
}
