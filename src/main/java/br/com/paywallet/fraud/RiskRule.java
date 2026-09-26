package br.com.paywallet.fraud;

/** Each triggered rule adds its points to the score of an outflow. */
public enum RiskRule {
    ACCOUNT_BLOCKED(100),
    WATCHLISTED_COUNTERPARTY(100),
    CARD_TESTING(50),
    VELOCITY(40),
    AMOUNT_ANOMALY(30),
    NEW_COUNTERPARTY(25),
    NEW_ACCOUNT(20),
    NIGHTTIME(15);

    final int points;

    RiskRule(int points) {
        this.points = points;
    }
}
