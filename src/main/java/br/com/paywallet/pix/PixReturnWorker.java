package br.com.paywallet.pix;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.merchant.ChargeService;
import br.com.paywallet.observability.PartnerCalls;
import br.com.paywallet.pix.PixGateway.ReturnOrder;

/**
 * Submits returns of Pix received from other institutions. Refused returns give the money back to the receiver;
 * when the PSP is unreachable the return stays pending and is retried on the next run.
 */
@Component
class PixReturnWorker {

    private static final Logger log = LoggerFactory.getLogger(PixReturnWorker.class);
    private static final int BATCH_SIZE = 20;

    private final PixReturnRepository returns;
    private final PixGateway gateway;
    private final PartnerCalls partners;
    private final LedgerService ledger;
    private final ChargeService charges;
    private final BalanceCache balanceCache;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PixReturnWorker(PixReturnRepository returns, PixGateway gateway, PartnerCalls partners, LedgerService ledger,
                    ChargeService charges, BalanceCache balanceCache, TransactionTemplate transactions, Clock clock) {
        this.returns = returns;
        this.gateway = gateway;
        this.partners = partners;
        this.ledger = ledger;
        this.charges = charges;
        this.balanceCache = balanceCache;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.pix.settlement-interval}")
    public void settle() {
        transactions.executeWithoutResult(status ->
                returns.lockPending(PageRequest.of(0, BATCH_SIZE)).forEach(this::submit));
    }

    private void submit(PixReturn pixReturn) {
        PixGateway.SubmitResult result;
        try {
            var order = new ReturnOrder(pixReturn.getReturnId(), pixReturn.getOriginalEndToEndId(),
                    pixReturn.getAmount(), pixReturn.getReason().name());
            result = partners.call("pix-psp", "return", () -> gateway.submitReturn(order));
        } catch (RuntimeException e) {
            log.warn("Pix return {} not submitted, will retry: {}", pixReturn.getReturnId(), e.getMessage());
            pixReturn.recordFailedAttempt(e.getMessage(), clock.instant());
            return;
        }
        if (result.accepted()) {
            pixReturn.complete(clock.instant());
            return;
        }
        var reversal = ledger.post(new PostCommand(LedgerTransactionType.PIX_RETURN_REVERSAL,
                "pix-return-reversal:" + pixReturn.getId(), "Pix return reversal " + pixReturn.getReturnId(),
                List.of(Leg.debit(AccountType.PIX_SETTLEMENT_ACCOUNT_ID, pixReturn.getAmount()),
                        Leg.credit(ledger.walletOf(pixReturn.getRequestedBy()).getId(), pixReturn.getAmount()))));
        pixReturn.fail(result.rejectionReason(), reversal.getId(), clock.instant());
        charges.recordPixReturn(pixReturn.getOriginalEndToEndId(), -pixReturn.getAmount());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                balanceCache.evict(pixReturn.getRequestedBy());
            }
        });
        log.info("Pix return {} refused ({}), amount given back to the receiver", pixReturn.getReturnId(),
                result.rejectionReason());
    }
}
