package br.com.paywallet.marketplace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class MarketplaceDtos {

    private MarketplaceDtos() {
    }

    public record ProductResponse(String id, Product.Category category, String brand, String name,
                                  List<BigDecimal> values, BigDecimal cashbackPercent) {
    }

    /** {@code phoneNumber}: area code and mobile number, digits only, for mobile recharges. */
    public record PurchaseRequest(
            @NotBlank String productId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value,
            @Pattern(regexp = "\\d{2}9\\d{8}", message = "must be an area code followed by a 9-digit mobile number")
            String phoneNumber) {
    }

    /** {@code voucherCode} is only returned when a single completed gift card order is requested by its buyer. */
    public record OrderResponse(UUID id, String productId, String productName, Product.Category category,
                                BigDecimal value, BigDecimal cashback, String phoneNumber,
                                MarketplaceOrder.Status status, String voucherCode, String failureReason,
                                Instant createdAt, Instant completedAt) {
    }

    public record PurchaseResult(OrderResponse order, boolean replayed) {
    }

    public record CashbackSummary(BigDecimal total, BigDecimal thisMonth) {
    }
}
