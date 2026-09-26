package br.com.paywallet.pix;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A received Pix sent back to its payer, total or partial. Amount in cents. */
@Entity
@Table(name = "pix_returns")
public class PixReturn {

    /** BE08 bank error, FR01 fraud, MD06 customer request, SL02 institution specific. */
    public enum Reason { BE08, FR01, MD06, SL02 }

    public enum Status { PENDING, COMPLETED, FAILED }

    @Id
    private UUID id;

    @Column(name = "return_id", nullable = false, updatable = false, unique = true, length = 32)
    private String returnId;

    @Column(name = "original_end_to_end_id", nullable = false, updatable = false, length = 32)
    private String originalEndToEndId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 4)
    private Reason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private PixPayment.Scope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 9)
    private Status status;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "ledger_transaction_id", nullable = false, updatable = false)
    private UUID ledgerTransactionId;

    @Column(name = "reversal_transaction_id")
    private UUID reversalTransactionId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected PixReturn() {
    }

    PixReturn(String returnId, PixPayment original, Long requestedBy, long amount, Reason reason,
              String idempotencyKey, UUID ledgerTransactionId, Instant now) {
        this.id = UUID.randomUUID();
        this.returnId = returnId;
        this.originalEndToEndId = original.getEndToEndId();
        this.requestedBy = requestedBy;
        this.amount = amount;
        this.reason = reason;
        this.scope = original.getScope();
        this.status = scope == PixPayment.Scope.INTERNAL ? Status.COMPLETED : Status.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdAt = now;
        this.updatedAt = now;
        this.settledAt = status == Status.COMPLETED ? now : null;
    }

    void complete(Instant now) {
        attempts++;
        status = Status.COMPLETED;
        settledAt = now;
        updatedAt = now;
    }

    void fail(String reason, UUID reversalTransactionId, Instant now) {
        attempts++;
        status = Status.FAILED;
        failureReason = reason;
        this.reversalTransactionId = reversalTransactionId;
        updatedAt = now;
    }

    void recordFailedAttempt(String error, Instant now) {
        attempts++;
        failureReason = error;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getReturnId() { return returnId; }
    public String getOriginalEndToEndId() { return originalEndToEndId; }
    public Long getRequestedBy() { return requestedBy; }
    public long getAmount() { return amount; }
    public Reason getReason() { return reason; }
    public PixPayment.Scope getScope() { return scope; }
    public Status getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getLedgerTransactionId() { return ledgerTransactionId; }
    public UUID getReversalTransactionId() { return reversalTransactionId; }
    public String getFailureReason() { return failureReason; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSettledAt() { return settledAt; }
}
