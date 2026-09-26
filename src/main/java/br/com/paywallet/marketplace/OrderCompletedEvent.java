package br.com.paywallet.marketplace;

import java.util.UUID;

/** Never carries the voucher code: events are stored and replicated in places not meant for secrets. */
public record OrderCompletedEvent(UUID orderId, Long userId, String email, String productName, long amountCents,
                                  long cashbackCents) {

    public static final String TYPE = "MarketplaceOrderCompleted";
}
