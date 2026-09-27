package br.com.paywallet.observability;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Wraps calls to external partners (PSP, banking partner, bureau, card processor...) in an observation, which
 * yields both the {@code paywallet.partner.requests} timer, tagged by partner, operation and error, and a span.
 */
@Component
public class PartnerCalls {

    private final ObservationRegistry registry;

    public PartnerCalls(ObservationRegistry registry) {
        this.registry = registry;
    }

    public <T> T call(String partner, String operation, Supplier<T> call) {
        return Observation.createNotStarted("paywallet.partner.requests", registry)
                .contextualName(partner + " " + operation)
                .lowCardinalityKeyValue("partner", partner)
                .lowCardinalityKeyValue("operation", operation)
                .observe(call);
    }

    public void run(String partner, String operation, Runnable call) {
        call(partner, operation, () -> {
            call.run();
            return null;
        });
    }
}
