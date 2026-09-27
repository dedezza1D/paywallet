package br.com.paywallet.pix;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.hotdata.DailyLimitService;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.observability.PartnerCalls;
import br.com.paywallet.pix.PixGateway.PixOrder;
import br.com.paywallet.user.UserService;

/**
 * Submits pending outgoing Pix to the network. Accepted payments complete; rejected ones are reversed in the
 * ledger and their daily limit released. When the network is unreachable the payment stays pending and is
 * retried on the next run.
 */
@Component
class PixSettlementWorker {

    private static final Logger log = LoggerFactory.getLogger(PixSettlementWorker.class);
    private static final int BATCH_SIZE = 20;

    private final PixPaymentRepository payments;
    private final PixGateway gateway;
    private final PartnerCalls partners;
    private final LedgerService ledger;
    private final UserService users;
    private final DailyLimitService limits;
    private final BalanceCache balanceCache;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PixSettlementWorker(PixPaymentRepository payments, PixGateway gateway, PartnerCalls partners, LedgerService ledger,
                        UserService users, DailyLimitService limits, BalanceCache balanceCache,
                        TransactionTemplate transactions, Clock clock) {
        this.payments = payments;
        this.gateway = gateway;
        this.partners = partners;
        this.ledger = ledger;
        this.users = users;
        this.limits = limits;
        this.balanceCache = balanceCache;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.pix.settlement-interval}")
    public void settle() {
        transactions.executeWithoutResult(status -> {
            List<PixPayment> pending = payments.lockPending(PageRequest.of(0, BATCH_SIZE));
            pending.forEach(this::submit);
        });
    }

    private void submit(PixPayment payment) {
        var payer = users.get(payment.getPayerUserId());
        PixGateway.SubmitResult result;
        try {
            var order = new PixOrder(payment.getEndToEndId(), payment.getKey(), payment.getAmount(),
                    payer.getFullName(), payer.getDocument(), payment.getDescription());
            result = partners.call("pix-psp", "submit", () -> gateway.submit(order));
        } catch (RuntimeException e) {
            log.warn("Pix {} not submitted, will retry: {}", payment.getEndToEndId(), e.getMessage());
            payment.recordFailedAttempt(e.getMessage(), clock.instant());
            return;
        }

        if (result.accepted()) {
            payment.complete(clock.instant());
            return;
        }
        var reversal = ledger.post(new PostCommand(LedgerTransactionType.PIX_OUT_REVERSAL,
                "pix-reversal:" + payment.getEndToEndId(), "Pix reversal " + payment.getEndToEndId(),
                List.of(Leg.debit(AccountType.PIX_SETTLEMENT_ACCOUNT_ID, payment.getAmount()),
                        Leg.credit(ledger.walletOf(payment.getPayerUserId()).getId(), payment.getAmount()))));
        payment.fail(result.rejectionReason(), reversal.getId(), clock.instant());
        afterCommit(() -> {
            balanceCache.evict(payment.getPayerUserId());
            try {
                limits.release(payment.getPayerUserId(), payment.getAmount());
            } catch (DataAccessException e) {
                log.error("Failed to release limit for reversed Pix {}: {}", payment.getEndToEndId(), e.getMessage());
            }
        });
        log.info("Pix {} rejected ({}), amount returned to the payer", payment.getEndToEndId(),
                result.rejectionReason());
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
