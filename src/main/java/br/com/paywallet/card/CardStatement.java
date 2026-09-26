package br.com.paywallet.card;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A closed credit card bill. OPEN while something is owed, PAID when settled in full, CARRIED when its unpaid
 * remainder moved to the next statement with revolving interest. Amounts are in cents.
 */
@Entity
@Table(name = "card_statements")
public class CardStatement {

    public enum Status { OPEN, PAID, CARRIED }

    @Id
    private UUID id;

    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    @Column(name = "closing_date", nullable = false, updatable = false)
    private LocalDate closingDate;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(nullable = false, updatable = false)
    private long total;

    @Column(name = "minimum_payment", nullable = false, updatable = false)
    private long minimumPayment;

    @Column(nullable = false)
    private long paid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 7)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CardStatement() {
    }

    CardStatement(UUID id, UUID cardId, LocalDate closingDate, LocalDate dueDate, long total, long minimumPayment,
                  Instant now) {
        this.id = id;
        this.cardId = cardId;
        this.closingDate = closingDate;
        this.dueDate = dueDate;
        this.total = total;
        this.minimumPayment = minimumPayment;
        this.status = total == 0 ? Status.PAID : Status.OPEN;
        this.createdAt = now;
    }

    long remaining() {
        return total - paid;
    }

    void pay(long amount) {
        paid += amount;
        if (paid == total) {
            status = Status.PAID;
        }
    }

    void carry() {
        status = Status.CARRIED;
    }

    public UUID getId() { return id; }
    public UUID getCardId() { return cardId; }
    public LocalDate getClosingDate() { return closingDate; }
    public LocalDate getDueDate() { return dueDate; }
    public long getTotal() { return total; }
    public long getMinimumPayment() { return minimumPayment; }
    public long getPaid() { return paid; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
