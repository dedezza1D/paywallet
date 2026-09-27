package br.com.paywallet.pix;

import java.time.Instant;
import java.util.UUID;

import br.com.paywallet.crypto.EncryptedString;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pix_payments")
public class PixPayment {

    public enum Direction { IN, OUT }

    /** INTERNAL: both parties are users of this institution and settlement is immediate. */
    public enum Scope { INTERNAL, EXTERNAL }

    public enum Status { PENDING, COMPLETED, FAILED }

    @Id
    private UUID id;

    @Column(name = "end_to_end_id", nullable = false, updatable = false, unique = true, length = 32)
    private String endToEndId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 3)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private Scope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 9)
    private Status status;

    @Column(name = "payer_user_id", updatable = false)
    private Long payerUserId;

    @Column(name = "payee_user_id", updatable = false)
    private Long payeeUserId;

    @Convert(converter = EncryptedString.class)
    @Column(name = "key_value", updatable = false)
    private String key;

    @Column(name = "counterparty_name", updatable = false, length = 140)
    private String counterpartyName;

    @Column(name = "counterparty_document", updatable = false, length = 20)
    private String counterpartyDocument;

    @Column(name = "counterparty_ispb", updatable = false, length = 8)
    private String counterpartyIspb;

    /** In cents. */
    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(updatable = false, length = 140)
    private String description;

    @Column(name = "idempotency_key", updatable = false, unique = true, length = 200)
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

    protected PixPayment() {
    }

    private PixPayment(String endToEndId, Direction direction, Scope scope, Status status, Long payerUserId,
                       Long payeeUserId, String key, String counterpartyName, String counterpartyDocument,
                       String counterpartyIspb, long amount, String description, String idempotencyKey,
                       UUID ledgerTransactionId, Instant now) {
        this.id = UUID.randomUUID();
        this.endToEndId = endToEndId;
        this.direction = direction;
        this.scope = scope;
        this.status = status;
        this.payerUserId = payerUserId;
        this.payeeUserId = payeeUserId;
        this.key = key;
        this.counterpartyName = counterpartyName;
        this.counterpartyDocument = counterpartyDocument;
        this.counterpartyIspb = counterpartyIspb;
        this.amount = amount;
        this.description = description;
        this.idempotencyKey = idempotencyKey;
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdAt = now;
        this.updatedAt = now;
        this.settledAt = status == Status.COMPLETED ? now : null;
    }

    static PixPayment internal(String endToEndId, Long payerUserId, Long payeeUserId, String key,
                               String payeeName, String payeeDocument, String ispb, long amount, String description,
                               String idempotencyKey, UUID ledgerTransactionId, Instant now) {
        return new PixPayment(endToEndId, Direction.OUT, Scope.INTERNAL, Status.COMPLETED, payerUserId, payeeUserId,
                key, payeeName, payeeDocument, ispb, amount, description, idempotencyKey, ledgerTransactionId, now);
    }

    static PixPayment outgoing(String endToEndId, Long payerUserId, String key, String payeeName,
                               String payeeDocument, String payeeIspb, long amount, String description,
                               String idempotencyKey, UUID ledgerTransactionId, Instant now) {
        return new PixPayment(endToEndId, Direction.OUT, Scope.EXTERNAL, Status.PENDING, payerUserId, null, key,
                payeeName, payeeDocument, payeeIspb, amount, description, idempotencyKey, ledgerTransactionId, now);
    }

    static PixPayment incoming(String endToEndId, Long payeeUserId, String key, String payerName,
                               String payerDocument, String payerIspb, long amount, String description,
                               UUID ledgerTransactionId, Instant now) {
        return new PixPayment(endToEndId, Direction.IN, Scope.EXTERNAL, Status.COMPLETED, null, payeeUserId, key,
                payerName, payerDocument, payerIspb, amount, description, null, ledgerTransactionId, now);
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

    public boolean involves(Long userId) {
        return userId.equals(payerUserId) || userId.equals(payeeUserId);
    }

    public UUID getId() { return id; }
    public String getEndToEndId() { return endToEndId; }
    public Direction getDirection() { return direction; }
    public Scope getScope() { return scope; }
    public Status getStatus() { return status; }
    public Long getPayerUserId() { return payerUserId; }
    public Long getPayeeUserId() { return payeeUserId; }
    public String getKey() { return key; }
    public String getCounterpartyName() { return counterpartyName; }
    public String getCounterpartyDocument() { return counterpartyDocument; }
    public String getCounterpartyIspb() { return counterpartyIspb; }
    public long getAmount() { return amount; }
    public String getDescription() { return description; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getLedgerTransactionId() { return ledgerTransactionId; }
    public UUID getReversalTransactionId() { return reversalTransactionId; }
    public String getFailureReason() { return failureReason; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSettledAt() { return settledAt; }
}
