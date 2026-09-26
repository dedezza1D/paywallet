package br.com.paywallet.credit;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Risk bands: minimum score, monthly interest rate and the highest limit the band allows. Within the band, the
 * limit is three times the customer's average monthly inflow, never below R$ 500.
 */
enum CreditPolicy {
    A(750, "0.0199", 2_000_000),
    B(600, "0.0349", 800_000),
    C(450, "0.0599", 300_000);

    static final long MIN_LIMIT = 50_000;

    final int minScore;
    final BigDecimal monthlyRate;
    final long maxLimit;

    CreditPolicy(int minScore, String monthlyRate, long maxLimit) {
        this.minScore = minScore;
        this.monthlyRate = new BigDecimal(monthlyRate);
        this.maxLimit = maxLimit;
    }

    static Optional<CreditPolicy> forScore(int score) {
        for (CreditPolicy band : values()) {
            if (score >= band.minScore) {
                return Optional.of(band);
            }
        }
        return Optional.empty();
    }

    long limitFor(long monthlyInflowCents) {
        long limit = Math.max(MIN_LIMIT, monthlyInflowCents * 3);
        return Math.min(maxLimit, limit / 10_000 * 10_000);
    }
}
