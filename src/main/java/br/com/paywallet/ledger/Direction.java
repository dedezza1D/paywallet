package br.com.paywallet.ledger;

/** CREDIT increases the account balance; DEBIT decreases it. */
public enum Direction {
    DEBIT,
    CREDIT;

    long signed(long amount) {
        return this == CREDIT ? amount : -amount;
    }
}
