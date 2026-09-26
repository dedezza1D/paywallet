package br.com.paywallet.card;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A purchase attempt, identified by the processor's authorization id. Amounts are in cents. */
@Entity
@Table(name = "card_authorizations")
public class CardAuthorization {

    public enum Status { APPROVED, DECLINED, CLEARED, REVERSED }

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(name = "merchant_name", nullable = false, updatable = false, length = 100)
    private String merchantName;

    @Column(updatable = false, length = 4)
    private String mcc;

    @Column(nullable = false, updatable = false)
    private int installments;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Status status;

    @Column(name = "response_code", nullable = false, updatable = false, length = 2)
    private String responseCode;

    @Column(name = "decline_reason", updatable = false, length = 100)
    private String declineReason;

    @Column(name = "cleared_amount")
    private Long clearedAmount;

    @Column(name = "hold_transaction_id", updatable = false)
    private UUID holdTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CardAuthorization() {
    }

    private CardAuthorization(String id, UUID cardId, long amount, String merchantName, String mcc, int installments,
                              Status status, String responseCode, String declineReason, UUID holdTransactionId,
                              Instant now) {
        this.id = id;
        this.cardId = cardId;
        this.amount = amount;
        this.merchantName = merchantName;
        this.mcc = mcc;
        this.installments = installments;
        this.status = status;
        this.responseCode = responseCode;
        this.declineReason = declineReason;
        this.holdTransactionId = holdTransactionId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static CardAuthorization approved(String id, UUID cardId, long amount, String merchantName, String mcc,
                                      int installments, UUID holdTransactionId, Instant now) {
        return new CardAuthorization(id, cardId, amount, merchantName, mcc, installments, Status.APPROVED, "00", null,
                holdTransactionId, now);
    }

    static CardAuthorization declined(String id, UUID cardId, long amount, String merchantName, String mcc,
                                      int installments, String responseCode, String reason, Instant now) {
        return new CardAuthorization(id, cardId, amount, merchantName, mcc, installments, Status.DECLINED, responseCode,
                reason, null, now);
    }

    void clear(long amount, Instant now) {
        this.status = Status.CLEARED;
        this.clearedAmount = amount;
        this.updatedAt = now;
    }

    void reverse(Instant now) {
        this.status = Status.REVERSED;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public UUID getCardId() { return cardId; }
    public long getAmount() { return amount; }
    public String getMerchantName() { return merchantName; }
    public String getMcc() { return mcc; }
    public int getInstallments() { return installments; }
    public Status getStatus() { return status; }
    public String getResponseCode() { return responseCode; }
    public String getDeclineReason() { return declineReason; }
    public Long getClearedAmount() { return clearedAmount; }
    public UUID getHoldTransactionId() { return holdTransactionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
