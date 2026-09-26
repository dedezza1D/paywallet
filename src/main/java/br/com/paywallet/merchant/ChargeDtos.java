package br.com.paywallet.merchant;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ChargeDtos {

    private ChargeDtos() {
    }

    /** {@code pixKey} defaults to one of the merchant's keys; without any, the charge is payable only in the app. */
    public record CreateChargeRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value,
            @Size(max = 140) String description,
            @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String reference,
            @Min(1) @Max(1440) Integer expiresInMinutes,
            String pixKey) {
    }

    public record ChargeResponse(UUID id, Charge.Status status, BigDecimal value, String description, String reference,
                                 String txid, String paymentLink, String brCode, Instant createdAt, Instant expiresAt,
                                 Instant paidAt, PaymentMethod paymentMethod, BigDecimal fee, BigDecimal net,
                                 BigDecimal refunded) {
    }

    public record RefundRequest(@DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value) {
    }

    /** What anyone holding the payment link may see. */
    public record PublicChargeResponse(String merchantName, BigDecimal value, String description, Charge.Status status,
                                       Instant expiresAt, String brCode) {
    }

    public record PaymentReceipt(UUID chargeId, Charge.Status status, BigDecimal value, String merchantName,
                                 UUID transactionId, Instant paidAt) {
    }

    public record CreateResult(ChargeResponse charge, boolean existing) {
    }

    public record PayResult(PaymentReceipt receipt, boolean replayed) {
    }

    public record Totals(long count, BigDecimal gross, BigDecimal fees, BigDecimal net) {
    }

    public record DailyTotals(LocalDate date, long count, BigDecimal gross, BigDecimal fees, BigDecimal net) {
    }

    public record Dashboard(LocalDate from, LocalDate to, Totals total, Totals wallet, Totals pix,
                            List<DailyTotals> daily) {
    }
}
