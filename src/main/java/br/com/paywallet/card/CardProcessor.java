package br.com.paywallet.card;

/**
 * Card processor (e.g. Dock, Pismo): holds card numbers in a PCI-DSS environment, talks to the card network and
 * calls this platform's webhooks to authorize, clear and reverse purchases.
 */
public interface CardProcessor {

    IssuedCard issue(Long userId, String holderName, Card.Type type);

    void updateStatus(String processorToken, Card.Status status);

    /** Everything the platform may keep about a card: never the full number or the CVV. */
    record IssuedCard(String processorToken, String last4, String brand, int expMonth, int expYear) {
    }
}
