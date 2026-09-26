package br.com.paywallet.bill;

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

import br.com.paywallet.bill.BillGateway.BillOrder;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.hotdata.DailyLimitService;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.user.UserService;

/**
 * Settles pending bill payments with the banking partner. Accepted payments are confirmed with the bank
 * authentication code; rejected ones are reversed and their daily limit released. When the partner is
 * unreachable the payment stays pending and is retried on the next run.
 */
@Component
class BillSettlementWorker {

    private static final Logger log = LoggerFactory.getLogger(BillSettlementWorker.class);
    private static final int BATCH_SIZE = 20;

    private final BillPaymentRepository payments;
    private final BillGateway gateway;
    private final LedgerService ledger;
    private final UserService users;
    private final DailyLimitService limits;
    private final BalanceCache balanceCache;
    private final TransactionTemplate transactions;
    private final Clock clock;

    BillSettlementWorker(BillPaymentRepository payments, BillGateway gateway, LedgerService ledger, UserService users,
                         DailyLimitService limits, BalanceCache balanceCache, TransactionTemplate transactions,
                         Clock clock) {
        this.payments = payments;
        this.gateway = gateway;
        this.ledger = ledger;
        this.users = users;
        this.limits = limits;
        this.balanceCache = balanceCache;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.bill.settlement-interval}")
    public void settle() {
        transactions.executeWithoutResult(status ->
                payments.lockPending(PageRequest.of(0, BATCH_SIZE)).forEach(this::submit));
    }

    private void submit(BillPayment payment) {
        var payer = users.get(payment.getPayerUserId());
        BillGateway.PaymentResult result;
        try {
            result = gateway.pay(new BillOrder(payment.getId().toString(), payment.getBarcode(), payment.getAmount(),
                    payer.getFullName(), payer.getDocument()));
        } catch (RuntimeException e) {
            log.warn("Bill payment {} not submitted, will retry: {}", payment.getId(), e.getMessage());
            payment.recordFailedAttempt(e.getMessage(), clock.instant());
            return;
        }

        if (result.accepted()) {
            payment.confirm(result.authenticationCode(), clock.instant());
            return;
        }
        var reversal = ledger.post(new PostCommand(LedgerTransactionType.BILL_PAYMENT_REVERSAL,
                "bill-reversal:" + payment.getId(), "Bill reversal " + payment.getId(),
                List.of(Leg.debit(AccountType.BILL_SETTLEMENT_ACCOUNT_ID, payment.getAmount()),
                        Leg.credit(ledger.walletOf(payment.getPayerUserId()).getId(), payment.getAmount()))));
        payment.fail(result.rejectionReason(), reversal.getId(), clock.instant());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                balanceCache.evict(payment.getPayerUserId());
                try {
                    limits.release(payment.getPayerUserId(), payment.getAmount());
                } catch (DataAccessException e) {
                    log.error("Failed to release limit for reversed bill payment {}: {}", payment.getId(),
                            e.getMessage());
                }
            }
        });
        log.info("Bill payment {} rejected ({}), amount returned to the payer", payment.getId(),
                result.rejectionReason());
    }
}
