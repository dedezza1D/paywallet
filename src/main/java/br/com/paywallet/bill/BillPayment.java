package br.com.paywallet.bill;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bill_payments")
public class BillPayment {

    public enum Status { PENDING, CONFIRMED, FAILED }

    @Id
    private UUID id;

    @Column(name = "payer_user_id", nullable = false, updatable = false)
    private Long payerUserId;

    @Column(nullable = false, updatable = false, length = 44)
    private String barcode;

    @Column(name = "digitable_line", nullable = false, updatable = false, length = 48)
    private String digitableLine;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 7)
    private BoletoCode.Kind kind;

    @Column(name = "bank_code", updatable = false, length = 3)
    private String bankCode;

    @Column(name = "beneficiary_name", updatable = false, length = 140)
    private String beneficiaryName;

    @Column(name = "beneficiary_document", updatable = false, length = 20)
    private String beneficiaryDocument;

    @Column(name = "due_date", updatable = false)
    private LocalDate dueDate;

    /** In cents; null for open-amount bills. */
    @Column(name = "nominal_amount", updatable = false)
    private Long nominalAmount;

    /** In cents, what the payer actually paid. */
    @Column(nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 9)
    private Status status;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "ledger_transaction_id", nullable = false, updatable = false)
    private UUID ledgerTransactionId;

    @Column(name = "reversal_transaction_id")
    private UUID reversalTransactionId;

    /** Bank authentication printed on the receipt, proving the bill was settled. */
    @Column(name = "authentication_code", length = 64)
    private String authenticationCode;

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

    protected BillPayment() {
    }

    BillPayment(Long payerUserId, BoletoCode code, BillGateway.BillQuote quote, String maskedDocument, long amount,
                String idempotencyKey, UUID ledgerTransactionId, Instant now) {
        this.id = UUID.randomUUID();
        this.payerUserId = payerUserId;
        this.barcode = code.barcode();
        this.digitableLine = code.digitableLine();
        this.kind = code.kind();
        this.bankCode = code.bankCode();
        this.beneficiaryName = quote.beneficiaryName();
        this.beneficiaryDocument = maskedDocument;
        this.dueDate = quote.dueDate() != null ? quote.dueDate() : code.dueDate();
        this.nominalAmount = quote.nominalAmountCents();
        this.amount = amount;
        this.status = Status.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void confirm(String authenticationCode, Instant now) {
        attempts++;
        status = Status.CONFIRMED;
        this.authenticationCode = authenticationCode;
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
    public Long getPayerUserId() { return payerUserId; }
    public String getBarcode() { return barcode; }
    public String getDigitableLine() { return digitableLine; }
    public BoletoCode.Kind getKind() { return kind; }
    public String getBankCode() { return bankCode; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public String getBeneficiaryDocument() { return beneficiaryDocument; }
    public LocalDate getDueDate() { return dueDate; }
    public Long getNominalAmount() { return nominalAmount; }
    public long getAmount() { return amount; }
    public Status getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getAuthenticationCode() { return authenticationCode; }
    public String getFailureReason() { return failureReason; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSettledAt() { return settledAt; }
}
