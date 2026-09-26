package br.com.paywallet.merchant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "charges")
public class Charge {

    public enum Status { PENDING, PAID, EXPIRED, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private Long merchantId;

    @Column(name = "public_token", nullable = false, updatable = false, unique = true, length = 32)
    private String publicToken;

    @Column(nullable = false, updatable = false, unique = true, length = 25)
    private String txid;

    /** In cents. */
    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(updatable = false, length = 140)
    private String description;

    @Column(updatable = false, length = 64)
    private String reference;

    @Column(name = "pix_key", updatable = false, length = 77)
    private String pixKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 9)
    private Status status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payer_user_id")
    private Long payerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 6)
    private PaymentMethod paymentMethod;

    @Column(name = "end_to_end_id", length = 32)
    private String endToEndId;

    @Column(name = "fee_bps")
    private Integer feeBps;

    @Column(name = "fee_amount")
    private Long feeAmount;

    @Column(name = "net_amount")
    private Long netAmount;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    protected Charge() {
    }

    Charge(Long merchantId, String publicToken, String txid, long amount, String description, String reference,
           String pixKey, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.publicToken = publicToken;
        this.txid = txid;
        this.amount = amount;
        this.description = description;
        this.reference = reference;
        this.pixKey = pixKey;
        this.status = Status.PENDING;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** A pending charge past its expiry is treated as expired even before the cleanup job marks it. */
    public Status effectiveStatus(Instant now) {
        return status == Status.PENDING && !now.isBefore(expiresAt) ? Status.EXPIRED : status;
    }

    void markPaid(PaymentMethod method, Long payerUserId, String endToEndId, Fee fee, UUID ledgerTransactionId,
                  Instant now) {
        this.status = Status.PAID;
        this.paymentMethod = method;
        this.payerUserId = payerUserId;
        this.endToEndId = endToEndId;
        this.feeBps = fee.bps();
        this.feeAmount = fee.feeCents();
        this.netAmount = fee.netCents();
        this.ledgerTransactionId = ledgerTransactionId;
        this.paidAt = now;
    }

    void cancel() {
        this.status = Status.CANCELLED;
    }

    public UUID getId() { return id; }
    public Long getMerchantId() { return merchantId; }
    public String getPublicToken() { return publicToken; }
    public String getTxid() { return txid; }
    public long getAmount() { return amount; }
    public String getDescription() { return description; }
    public String getReference() { return reference; }
    public String getPixKey() { return pixKey; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPaidAt() { return paidAt; }
    public Long getPayerUserId() { return payerUserId; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public String getEndToEndId() { return endToEndId; }
    public Integer getFeeBps() { return feeBps; }
    public Long getFeeAmount() { return feeAmount; }
    public Long getNetAmount() { return netAmount; }
    public UUID getLedgerTransactionId() { return ledgerTransactionId; }
}
