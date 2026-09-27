package br.com.paywallet.marketplace;

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

/** Amounts in cents. {@code voucherCode} holds the encrypted gift card code. */
@Entity
@Table(name = "marketplace_orders")
public class MarketplaceOrder {

    public enum Status { PENDING, COMPLETED, FAILED }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false, updatable = false, length = 40)
    private String productId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private long commission;

    @Column(nullable = false, updatable = false)
    private long cashback;

    @Convert(converter = EncryptedString.class)
    @Column(name = "phone_number", updatable = false)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 9)
    private Status status;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "ledger_transaction_id", nullable = false, updatable = false)
    private UUID ledgerTransactionId;

    @Column(name = "reversal_transaction_id")
    private UUID reversalTransactionId;

    @Column(name = "voucher_code", length = 512)
    private String voucherCode;

    @Column(name = "provider_reference", length = 64)
    private String providerReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected MarketplaceOrder() {
    }

    MarketplaceOrder(Long userId, Product product, long amount, String phoneNumber, String idempotencyKey,
                     UUID ledgerTransactionId, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.productId = product.getId();
        this.amount = amount;
        this.commission = product.commissionFor(amount);
        this.cashback = product.cashbackFor(amount);
        this.phoneNumber = phoneNumber;
        this.status = Status.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void complete(String encryptedVoucherCode, String providerReference, Instant now) {
        this.status = Status.COMPLETED;
        this.voucherCode = encryptedVoucherCode;
        this.providerReference = providerReference;
        this.attempts++;
        this.updatedAt = now;
        this.completedAt = now;
    }

    void fail(String reason, UUID reversalTransactionId, Instant now) {
        this.status = Status.FAILED;
        this.failureReason = reason;
        this.reversalTransactionId = reversalTransactionId;
        this.attempts++;
        this.updatedAt = now;
    }

    void recordFailedAttempt(String reason, Instant now) {
        this.failureReason = reason;
        this.attempts++;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public String getProductId() { return productId; }
    public long getAmount() { return amount; }
    public long getCommission() { return commission; }
    public long getCashback() { return cashback; }
    public String getPhoneNumber() { return phoneNumber; }
    public Status getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getLedgerTransactionId() { return ledgerTransactionId; }
    public UUID getReversalTransactionId() { return reversalTransactionId; }
    public String getVoucherCode() { return voucherCode; }
    public String getProviderReference() { return providerReference; }
    public String getFailureReason() { return failureReason; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
