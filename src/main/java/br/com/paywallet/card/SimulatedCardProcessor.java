package br.com.paywallet.card;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.cards.processor", havingValue = "simulated", matchIfMissing = true)
class SimulatedCardProcessor implements CardProcessor {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCardProcessor.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;

    SimulatedCardProcessor(Clock clock) {
        this.clock = clock;
        log.warn("Using the simulated card processor: no real cards are issued");
    }

    @Override
    public IssuedCard issue(Long userId, String holderName, Card.Type type) {
        LocalDate expiry = LocalDate.now(clock).plusYears(5);
        return new IssuedCard("tok_" + UUID.randomUUID().toString().replace("-", ""),
                "%04d".formatted(RANDOM.nextInt(10_000)), "MASTERCARD", expiry.getMonthValue(), expiry.getYear());
    }

    @Override
    public void updateStatus(String processorToken, Card.Status status) {
        log.info("Card {} is now {}", processorToken, status);
    }
}
