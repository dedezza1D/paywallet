package br.com.paywallet.credit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A personal loan. Its installments live in loan_installments. Amounts in cents. */
@Entity
@Table(name = "loans")
public class Loan {

    public enum Status { ACTIVE, PAID_OFF }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "analysis_id", nullable = false, updatable = false)
    private UUID analysisId;

    /** What the customer received. */
    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private long iof;

    /** Principal of the installments: amount plus financed IOF. */
    @Column(nullable = false, updatable = false)
    private long financed;

    @Column(name = "monthly_rate", nullable = false, updatable = false)
    private BigDecimal monthlyRate;

    @Column(name = "cet_monthly", nullable = false, updatable = false)
    private BigDecimal cetMonthly;

    @Column(name = "cet_annual", nullable = false, updatable = false)
    private BigDecimal cetAnnual;

    @Column(nullable = false, updatable = false)
    private int installments;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Status status;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "ledger_transaction_id", nullable = false, updatable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_off_at")
    private Instant paidOffAt;

    protected Loan() {
    }

    Loan(Long userId, UUID analysisId, LoanMath.Quote quote, String idempotencyKey, UUID ledgerTransactionId,
         Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.analysisId = analysisId;
        this.amount = quote.amount();
        this.iof = quote.iof();
        this.financed = quote.financed();
        this.monthlyRate = quote.monthlyRate();
        this.cetMonthly = quote.cetMonthly();
        this.cetAnnual = quote.cetAnnual();
        this.installments = quote.schedule().size();
        this.status = Status.ACTIVE;
        this.idempotencyKey = idempotencyKey;
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdAt = now;
    }

    void payOff(Instant now) {
        status = Status.PAID_OFF;
        paidOffAt = now;
    }

    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public long getAmount() { return amount; }
    public long getIof() { return iof; }
    public long getFinanced() { return financed; }
    public BigDecimal getMonthlyRate() { return monthlyRate; }
    public BigDecimal getCetMonthly() { return cetMonthly; }
    public BigDecimal getCetAnnual() { return cetAnnual; }
    public int getInstallments() { return installments; }
    public Status getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPaidOffAt() { return paidOffAt; }
}
