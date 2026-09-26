package br.com.paywallet.marketplace;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "marketplace_products")
public class Product {

    public enum Category { GIFT_CARD, MOBILE_RECHARGE }

    @Id
    @Column(length = 40)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Category category;

    @Column(nullable = false, length = 40)
    private String brand;

    @Column(nullable = false, length = 80)
    private String name;

    /** Comma-separated face values in cents. */
    @Column(nullable = false, length = 200)
    private String denominations;

    @Column(name = "commission_bps", nullable = false)
    private int commissionBps;

    @Column(name = "cashback_bps", nullable = false)
    private int cashbackBps;

    @Column(nullable = false)
    private boolean active;

    protected Product() {
    }

    List<Long> denominationList() {
        return Arrays.stream(denominations.split(",")).map(String::trim).map(Long::valueOf).toList();
    }

    long commissionFor(long amount) {
        return amount * commissionBps / 10_000;
    }

    long cashbackFor(long amount) {
        return amount * cashbackBps / 10_000;
    }

    public String getId() { return id; }
    public Category getCategory() { return category; }
    public String getBrand() { return brand; }
    public String getName() { return name; }
    public int getCommissionBps() { return commissionBps; }
    public int getCashbackBps() { return cashbackBps; }
    public boolean isActive() { return active; }
}
