package br.com.paywallet.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class RiskEvaluatorTest {

    private static final FraudProperties PROPS = new FraudProperties(50, 80, Duration.ofMinutes(10), 5, 5, 3,
            new BigDecimal("500.00"), new BigDecimal("1000.00"), 22, 6, new BigDecimal("1000.00"), 7,
            new BigDecimal("500.00"), Duration.ofMinutes(10), 3, ZoneId.of("America/Sao_Paulo"));

    private static final LocalTime NOON = LocalTime.NOON;

    /** A 90-day-old account paying R$ 100 to a known receiver at noon, with a usual amount of R$ 100. */
    private static RiskEvaluator.Signals usual(long amount) {
        return new RiskEvaluator.Signals(amount, false, false, true, true, 1, 10, 10_000, 90, NOON, 0, false);
    }

    @Test
    void usualPaymentIsApproved() {
        var result = RiskEvaluator.evaluate(usual(10_000), PROPS);

        assertThat(result.decision()).isEqualTo(Assessment.Decision.APPROVE);
        assertThat(result.score()).isZero();
        assertThat(result.rules()).isEmpty();
    }

    @Test
    void largeFirstPaymentFromANewAccountGoesToReview() {
        var s = new RiskEvaluator.Signals(300_000, false, false, false, true, 1, 3, 10_000, 2, NOON, 0, false);

        var result = RiskEvaluator.evaluate(s, PROPS);

        assertThat(result.rules()).containsExactly(RiskRule.AMOUNT_ANOMALY, RiskRule.NEW_COUNTERPARTY,
                RiskRule.NEW_ACCOUNT);
        assertThat(result.score()).isEqualTo(75);
        assertThat(result.decision()).isEqualTo(Assessment.Decision.REVIEW);
    }

    @Test
    void addingVelocityTurnsReviewIntoDecline() {
        var s = new RiskEvaluator.Signals(300_000, false, false, false, true, 6, 3, 10_000, 2, NOON, 0, false);

        var result = RiskEvaluator.evaluate(s, PROPS);

        assertThat(result.rules()).startsWith(RiskRule.VELOCITY);
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.decision()).isEqualTo(Assessment.Decision.DECLINE);
    }

    @Test
    void amountAnomalyNeedsHistoryAndTheFloor() {
        var fewPayments = new RiskEvaluator.Signals(100_000, false, false, true, true, 1, 2, 1_000, 90, NOON, 0, false);
        var belowFloor = new RiskEvaluator.Signals(49_999, false, false, true, true, 1, 10, 1_000, 90, NOON, 0, false);
        var anomaly = new RiskEvaluator.Signals(50_001, false, false, true, true, 1, 10, 10_000, 90, NOON, 0, false);

        assertThat(RiskEvaluator.evaluate(fewPayments, PROPS).rules()).isEmpty();
        assertThat(RiskEvaluator.evaluate(belowFloor, PROPS).rules()).isEmpty();
        assertThat(RiskEvaluator.evaluate(anomaly, PROPS).rules()).containsExactly(RiskRule.AMOUNT_ANOMALY);
    }

    @Test
    void nightWindowWrapsAroundMidnight() {
        long amount = 100_000;
        for (int hour : new int[] {22, 23, 0, 5}) {
            var s = new RiskEvaluator.Signals(amount, false, false, true, true, 1, 10, amount, 90,
                    LocalTime.of(hour, 30), 0, false);
            assertThat(RiskEvaluator.evaluate(s, PROPS).rules()).as("hour %d", hour).containsExactly(RiskRule.NIGHTTIME);
        }
        for (int hour : new int[] {6, 12, 21}) {
            var s = new RiskEvaluator.Signals(amount, false, false, true, true, 1, 10, amount, 90,
                    LocalTime.of(hour, 30), 0, false);
            assertThat(RiskEvaluator.evaluate(s, PROPS).rules()).as("hour %d", hour).isEmpty();
        }
    }

    @Test
    void blockedAccountsAndWatchlistedReceiversAreAlwaysDeclined() {
        var blocked = new RiskEvaluator.Signals(100, true, false, true, true, 1, 10, 100, 90, NOON, 0, false);
        var watchlisted = new RiskEvaluator.Signals(100, false, true, true, true, 1, 10, 100, 90, NOON, 0, false);

        assertThat(RiskEvaluator.evaluate(blocked, PROPS).decision()).isEqualTo(Assessment.Decision.DECLINE);
        assertThat(RiskEvaluator.evaluate(watchlisted, PROPS).rules()).containsExactly(RiskRule.WATCHLISTED_COUNTERPARTY);
    }

    @Test
    void cardTestingOnlyAppliesToCards() {
        var card = new RiskEvaluator.Signals(1_000, false, false, true, true, 1, 10, 1_000, 90, NOON, 3, true);
        var pix = new RiskEvaluator.Signals(1_000, false, false, true, true, 1, 10, 1_000, 90, NOON, 3, false);

        assertThat(RiskEvaluator.evaluate(card, PROPS).rules()).containsExactly(RiskRule.CARD_TESTING);
        assertThat(RiskEvaluator.evaluate(card, PROPS).decision()).isEqualTo(Assessment.Decision.REVIEW);
        assertThat(RiskEvaluator.evaluate(pix, PROPS).rules()).isEmpty();
    }
}
