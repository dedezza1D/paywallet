package br.com.paywallet.credit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in bureau for local environments: a stable score between 450 and 949 derived from the document, and a
 * restriction for documents ending in 00.
 */
@Component
@ConditionalOnProperty(name = "app.credit.bureau", havingValue = "simulated", matchIfMissing = true)
class SimulatedCreditBureau implements CreditBureau {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCreditBureau.class);

    SimulatedCreditBureau() {
        log.warn("Using the simulated credit bureau: scores are not real");
    }

    @Override
    public BureauReport report(String document) {
        int digitSum = document.chars().map(c -> c - '0').sum();
        return new BureauReport(450 + (digitSum * 37) % 500, document.endsWith("00"));
    }
}
