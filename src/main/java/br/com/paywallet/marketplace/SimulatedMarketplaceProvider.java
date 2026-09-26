package br.com.paywallet.marketplace;

import java.security.SecureRandom;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for the product aggregator in local environments. Gift cards get a random code; recharges to phone
 * numbers ending in 0000 are refused, to exercise the refund path.
 */
@Component
@ConditionalOnProperty(name = "app.marketplace.provider", havingValue = "simulated", matchIfMissing = true)
class SimulatedMarketplaceProvider implements MarketplaceProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMarketplaceProvider.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    SimulatedMarketplaceProvider() {
        log.warn("Using the simulated marketplace provider: no real product is delivered");
    }

    @Override
    public FulfillmentResult fulfill(FulfillmentOrder order) {
        String reference = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        if (order.phoneNumber() != null) {
            return order.phoneNumber().endsWith("0000")
                    ? FulfillmentResult.rejected("Phone number not served by the carrier")
                    : FulfillmentResult.ok(null, reference);
        }
        var code = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return FulfillmentResult.ok(code.toString(), reference);
    }
}
