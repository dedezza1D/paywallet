package br.com.paywallet.credit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "credit_analyses")
public class CreditAnalysis {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "bureau_score", nullable = false, updatable = false)
    private int bureauScore;

    @Column(name = "internal_score", nullable = false, updatable = false)
    private int internalScore;

    @Column(nullable = false, updatable = false)
    private int score;

    @Column(name = "risk_band", updatable = false, length = 1)
    private String riskBand;

    @Column(nullable = false, updatable = false)
    private boolean approved;

    /** In cents. */
    @Column(name = "credit_limit", nullable = false, updatable = false)
    private long creditLimit;

    @Column(name = "monthly_rate", updatable = false)
    private BigDecimal monthlyRate;

    /** One reason per line: the factors that lowered the score or rejected the request. */
    @Column(nullable = false, updatable = false)
    private String reasons;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected CreditAnalysis() {
    }

    CreditAnalysis(Long userId, int bureauScore, int internalScore, int score, CreditPolicy band, long creditLimit,
                   List<String> reasons, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.bureauScore = bureauScore;
        this.internalScore = internalScore;
        this.score = score;
        this.riskBand = band == null ? null : band.name();
        this.approved = band != null;
        this.creditLimit = band == null ? 0 : creditLimit;
        this.monthlyRate = band == null ? null : band.monthlyRate;
        this.reasons = String.join("\n", reasons);
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public List<String> reasonList() {
        return reasons.isEmpty() ? List.of() : Arrays.asList(reasons.split("\n"));
    }

    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public int getBureauScore() { return bureauScore; }
    public int getInternalScore() { return internalScore; }
    public int getScore() { return score; }
    public String getRiskBand() { return riskBand; }
    public boolean isApproved() { return approved; }
    public long getCreditLimit() { return creditLimit; }
    public BigDecimal getMonthlyRate() { return monthlyRate; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
