package br.com.paywallet.card;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cards")
public class Card {

    public enum Type { DEBIT, CREDIT }

    public enum Status { ACTIVE, BLOCKED, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 9)
    private Status status;

    @Column(name = "processor_token", nullable = false, updatable = false, unique = true, length = 64)
    private String processorToken;

    @Column(nullable = false, updatable = false, length = 4)
    private String last4;

    @Column(nullable = false, updatable = false, length = 20)
    private String brand;

    @Column(name = "exp_month", nullable = false, updatable = false)
    private int expMonth;

    @Column(name = "exp_year", nullable = false, updatable = false)
    private int expYear;

    /** In cents; credit cards only. */
    @Column(name = "credit_limit", updatable = false)
    private Long creditLimit;

    @Column(name = "closing_day", updatable = false)
    private Integer closingDay;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Card() {
    }

    Card(Long userId, Type type, CardProcessor.IssuedCard issued, Long creditLimit, Integer closingDay, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.type = type;
        this.status = Status.ACTIVE;
        this.processorToken = issued.processorToken();
        this.last4 = issued.last4();
        this.brand = issued.brand();
        this.expMonth = issued.expMonth();
        this.expYear = issued.expYear();
        this.creditLimit = creditLimit;
        this.closingDay = closingDay;
        this.createdAt = now;
    }

    boolean isExpired(LocalDate today) {
        return YearMonth.of(expYear, expMonth).isBefore(YearMonth.from(today));
    }

    void changeStatus(Status status) {
        this.status = status;
    }

    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public String getProcessorToken() { return processorToken; }
    public String getLast4() { return last4; }
    public String getBrand() { return brand; }
    public int getExpMonth() { return expMonth; }
    public int getExpYear() { return expYear; }
    public Long getCreditLimit() { return creditLimit; }
    public Integer getClosingDay() { return closingDay; }
    public Instant getCreatedAt() { return createdAt; }
}
