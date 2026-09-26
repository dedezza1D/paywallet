package br.com.paywallet.fraud;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import br.com.paywallet.ledger.Money;

/** Pure scoring: turns the signals gathered for an outflow into a decision. */
final class RiskEvaluator {

    /**
     * @param recentOutflows   outflow attempts in the velocity window, this one included
     * @param historyCount     outflows over the last 90 days
     * @param historyAverage   their average amount, in cents
     * @param recentCardDeclines declined card authorizations in the card window (card channel only)
     */
    record Signals(long amountCents, boolean blocked, boolean watchlisted, boolean knownCounterparty,
                   boolean hasCounterparty, long recentOutflows, long historyCount, long historyAverage,
                   long accountAgeDays, LocalTime localTime, long recentCardDeclines, boolean card) {
    }

    private RiskEvaluator() {
    }

    static Assessment evaluate(Signals s, FraudProperties p) {
        List<RiskRule> rules = new ArrayList<>();
        if (s.blocked()) {
            rules.add(RiskRule.ACCOUNT_BLOCKED);
        }
        if (s.watchlisted()) {
            rules.add(RiskRule.WATCHLISTED_COUNTERPARTY);
        }
        if (s.card() && s.recentCardDeclines() >= p.cardDeclinesMax()) {
            rules.add(RiskRule.CARD_TESTING);
        }
        if (s.recentOutflows() > p.velocityMaxCount()) {
            rules.add(RiskRule.VELOCITY);
        }
        if (s.historyCount() >= p.amountAnomalyMinHistory()
                && s.amountCents() >= Money.toCents(p.amountAnomalyFloor())
                && s.amountCents() > s.historyAverage() * p.amountAnomalyMultiplier()) {
            rules.add(RiskRule.AMOUNT_ANOMALY);
        }
        if (s.hasCounterparty() && !s.knownCounterparty() && s.amountCents() >= Money.toCents(p.newCounterpartyAmount())) {
            rules.add(RiskRule.NEW_COUNTERPARTY);
        }
        if (s.accountAgeDays() < p.newAccountDays() && s.amountCents() >= Money.toCents(p.newAccountAmount())) {
            rules.add(RiskRule.NEW_ACCOUNT);
        }
        if (isNight(s.localTime(), p) && s.amountCents() >= Money.toCents(p.nightAmount())) {
            rules.add(RiskRule.NIGHTTIME);
        }
        int score = Math.min(100, rules.stream().mapToInt(r -> r.points).sum());
        var decision = score >= p.declineScore() ? Assessment.Decision.DECLINE
                : score >= p.reviewScore() ? Assessment.Decision.REVIEW : Assessment.Decision.APPROVE;
        return new Assessment(decision, score, List.copyOf(rules));
    }

    private static boolean isNight(LocalTime time, FraudProperties p) {
        int start = p.nightStartHour();
        int end = p.nightEndHour();
        if (start == end) {
            return false;
        }
        int hour = time.getHour();
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }
}
