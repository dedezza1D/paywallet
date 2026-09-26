package br.com.paywallet.marketplace;

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
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.marketplace.MarketplaceProvider.FulfillmentOrder;
import br.com.paywallet.messaging.Topics;
import br.com.paywallet.outbox.OutboxWriter;
import br.com.paywallet.user.UserService;

/**
 * Delivers pending orders through the provider. On delivery the commission moves to SYSTEM_FEES and the cashback
 * is credited to the buyer from SYSTEM_CASHBACK; refused orders are refunded and their daily limit released.
 * When the provider is unreachable the order stays pending and is retried on the next run.
 */
@Component
class FulfillmentWorker {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentWorker.class);
    private static final int BATCH_SIZE = 20;

    private final MarketplaceOrderRepository orders;
    private final ProductRepository products;
    private final MarketplaceProvider provider;
    private final VoucherCipher cipher;
    private final LedgerService ledger;
    private final UserService users;
    private final DailyLimitService limits;
    private final BalanceCache balanceCache;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    FulfillmentWorker(MarketplaceOrderRepository orders, ProductRepository products, MarketplaceProvider provider,
                      VoucherCipher cipher, LedgerService ledger, UserService users, DailyLimitService limits,
                      BalanceCache balanceCache, OutboxWriter outbox, TransactionTemplate transactions, Clock clock) {
        this.orders = orders;
        this.products = products;
        this.provider = provider;
        this.cipher = cipher;
        this.ledger = ledger;
        this.users = users;
        this.limits = limits;
        this.balanceCache = balanceCache;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.marketplace.fulfillment-interval}")
    public void fulfill() {
        transactions.executeWithoutResult(status ->
                orders.lockPending(PageRequest.of(0, BATCH_SIZE)).forEach(this::deliver));
    }

    private void deliver(MarketplaceOrder order) {
        MarketplaceProvider.FulfillmentResult result;
        try {
            result = provider.fulfill(new FulfillmentOrder(order.getId().toString(), order.getProductId(),
                    order.getAmount(), order.getPhoneNumber()));
        } catch (RuntimeException e) {
            log.warn("Order {} not delivered, will retry: {}", order.getId(), e.getMessage());
            order.recordFailedAttempt(e.getMessage(), clock.instant());
            return;
        }
        if (result.accepted()) {
            complete(order, result);
        } else {
            refund(order, result.rejectionReason());
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                balanceCache.evict(order.getUserId());
            }
        });
    }

    private void complete(MarketplaceOrder order, MarketplaceProvider.FulfillmentResult result) {
        var product = products.findById(order.getProductId()).orElseThrow();
        if (order.getCommission() > 0) {
            ledger.post(new PostCommand(LedgerTransactionType.MARKETPLACE_COMMISSION,
                    "marketplace-commission:" + order.getId(), "Commission on " + product.getName(),
                    List.of(Leg.debit(AccountType.MARKETPLACE_SETTLEMENT_ACCOUNT_ID, order.getCommission()),
                            Leg.credit(AccountType.FEES_ACCOUNT_ID, order.getCommission()))));
        }
        if (order.getCashback() > 0) {
            ledger.post(new PostCommand(LedgerTransactionType.CASHBACK_CREDIT, "cashback:" + order.getId(),
                    "Cashback on " + product.getName(),
                    List.of(Leg.debit(AccountType.CASHBACK_ACCOUNT_ID, order.getCashback()),
                            Leg.credit(ledger.walletOf(order.getUserId()).getId(), order.getCashback()))));
        }
        order.complete(result.voucherCode() == null ? null : cipher.encrypt(result.voucherCode()),
                result.providerReference(), clock.instant());
        var user = users.get(order.getUserId());
        outbox.append(Topics.MARKETPLACE_ORDERS_COMPLETED, user.getId().toString(), OrderCompletedEvent.TYPE,
                new OrderCompletedEvent(order.getId(), user.getId(), user.getEmail(), product.getName(),
                        order.getAmount(), order.getCashback()));
    }

    private void refund(MarketplaceOrder order, String reason) {
        var refund = ledger.post(new PostCommand(LedgerTransactionType.MARKETPLACE_REFUND,
                "marketplace-refund:" + order.getId(), "Refund of order " + order.getId(),
                List.of(Leg.debit(AccountType.MARKETPLACE_SETTLEMENT_ACCOUNT_ID, order.getAmount()),
                        Leg.credit(ledger.walletOf(order.getUserId()).getId(), order.getAmount()))));
        order.fail(reason, refund.getId(), clock.instant());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    limits.release(order.getUserId(), order.getAmount());
                } catch (DataAccessException e) {
                    log.error("Failed to release limit for refunded order {}: {}", order.getId(), e.getMessage());
                }
            }
        });
        log.info("Order {} refused ({}), amount refunded to the buyer", order.getId(), reason);
    }
}
