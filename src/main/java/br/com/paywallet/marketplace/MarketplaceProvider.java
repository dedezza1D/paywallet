package br.com.paywallet.marketplace;

/**
 * Aggregator of digital products (e.g. gift card distributors, carrier recharge hubs). The platform sells at
 * face value and the provider pays a commission per sale.
 */
public interface MarketplaceProvider {

    /** @throws br.com.paywallet.exception.ExternalServiceException when the provider cannot be reached */
    FulfillmentResult fulfill(FulfillmentOrder order);

    /** {@code phoneNumber} is set for mobile recharges only. */
    record FulfillmentOrder(String orderId, String productId, long amountCents, String phoneNumber) {
    }

    /** {@code voucherCode} is set for gift cards; recharges are credited straight to the phone line. */
    record FulfillmentResult(boolean accepted, String voucherCode, String providerReference, String rejectionReason) {

        public static FulfillmentResult ok(String voucherCode, String providerReference) {
            return new FulfillmentResult(true, voucherCode, providerReference, null);
        }

        public static FulfillmentResult rejected(String reason) {
            return new FulfillmentResult(false, null, null, reason);
        }
    }
}
