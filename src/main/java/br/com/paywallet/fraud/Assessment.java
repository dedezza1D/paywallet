package br.com.paywallet.fraud;

import java.util.List;

public record Assessment(Decision decision, int score, List<RiskRule> rules) {

    public enum Decision { APPROVE, REVIEW, DECLINE }

    public boolean declined() {
        return decision == Decision.DECLINE;
    }
}
