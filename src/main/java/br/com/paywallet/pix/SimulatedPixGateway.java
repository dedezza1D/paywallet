package br.com.paywallet.pix;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a PSP in local environments. Every key not found locally resolves to an account at a
 * fictitious bank, except keys starting with "unknown"; payments to keys starting with "reject" are refused, and
 * so are returns whose amount ends in 99 cents.
 */
@Component
@ConditionalOnProperty(name = "app.pix.gateway", havingValue = "simulated", matchIfMissing = true)
class SimulatedPixGateway implements PixGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPixGateway.class);

    SimulatedPixGateway() {
        log.warn("Using the simulated Pix gateway: no money leaves this system");
    }

    @Override
    public Optional<ExternalAccount> lookup(String key) {
        if (key.startsWith("unknown")) {
            return Optional.empty();
        }
        return Optional.of(new ExternalAccount("Simulated Holder", "98765432100", "99999999", "Simulated Bank"));
    }

    @Override
    public SubmitResult submit(PixOrder order) {
        return order.key().startsWith("reject")
                ? SubmitResult.rejected("Receiving account closed")
                : SubmitResult.ok();
    }

    @Override
    public SubmitResult submitReturn(ReturnOrder order) {
        return order.amountCents() % 100 == 99
                ? SubmitResult.rejected("Original account closed")
                : SubmitResult.ok();
    }
}
