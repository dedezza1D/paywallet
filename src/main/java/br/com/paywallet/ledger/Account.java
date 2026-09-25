package br.com.paywallet.ledger;

import java.time.Instant;
import java.util.UUID;

import br.com.paywallet.exception.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** {@code balance} is a snapshot of the sum of postings, updated in the same transaction as each {@link Posting}. */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "owner_id", updatable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private AccountType type;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency = "BRL";

    /** In cents. */
    @Column(nullable = false)
    private long balance;

    @Column(name = "allow_negative", nullable = false, updatable = false)
    private boolean allowNegative;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    static Account userWallet(Long ownerId) {
        var a = new Account();
        a.id = UUID.randomUUID();
        a.ownerId = ownerId;
        a.type = AccountType.USER_WALLET;
        a.allowNegative = false;
        a.createdAt = Instant.now();
        a.updatedAt = a.createdAt;
        return a;
    }

    long apply(Direction direction, long amount) {
        long next = Math.addExact(balance, direction.signed(amount));
        if (next < 0 && !allowNegative) {
            throw new InsufficientFundsException();
        }
        balance = next;
        updatedAt = Instant.now();
        return next;
    }

    public UUID getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public long getBalance() { return balance; }
    public boolean isAllowNegative() { return allowNegative; }
    public Instant getUpdatedAt() { return updatedAt; }
}
