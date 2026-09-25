package br.com.paywallet.pix;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.paywallet.ledger.Money;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PixDtos {

    private PixDtos() {
    }

    /** {@code value} is ignored for EVP keys, which are generated. */
    public record RegisterKeyRequest(@NotNull PixKeyType type, String value) {
    }

    public record PixKeyResponse(UUID id, PixKeyType type, String value, Instant createdAt) {

        static PixKeyResponse from(PixKey key) {
            return new PixKeyResponse(key.getId(), key.getType(), key.getValue(), key.getCreatedAt());
        }
    }

    public record StaticQrCodeRequest(
            @NotBlank String key,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal value,
            @Size(max = 50) String description,
            @Pattern(regexp = "[A-Za-z0-9]{1,25}") String txid) {
    }

    public record QrCodeResponse(String brCode) {
    }

    /** Exactly one of {@code key} or {@code brCode}. {@code value} is required unless the QR code fixes it. */
    public record SendPixRequest(
            String key,
            String brCode,
            @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value,
            @Size(max = 140) String description) {
    }

    public record PixPaymentResponse(String endToEndId, PixPayment.Direction direction, PixPayment.Scope scope,
                                     PixPayment.Status status, BigDecimal value, String counterpartyName,
                                     String counterpartyDocument, String counterpartyIspb, String description,
                                     String failureReason, Instant createdAt, Instant settledAt) {

        static PixPaymentResponse from(PixPayment p) {
            return new PixPaymentResponse(p.getEndToEndId(), p.getDirection(), p.getScope(), p.getStatus(),
                    Money.fromCents(p.getAmount()), p.getCounterpartyName(), p.getCounterpartyDocument(),
                    p.getCounterpartyIspb(), p.getDescription(), p.getFailureReason(), p.getCreatedAt(),
                    p.getSettledAt());
        }
    }

    public record SendResult(PixPaymentResponse payment, boolean replayed) {
    }

    /** Sent by the PSP when a Pix from another institution credits one of our keys. */
    public record IncomingPix(
            @NotBlank @Size(min = 32, max = 32) String endToEndId,
            @NotBlank String key,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value,
            @NotBlank String payerName,
            String payerDocument,
            @NotBlank @Pattern(regexp = "\\d{8}") String payerIspb,
            @Size(max = 140) String description,
            @Size(max = 25) String txid) {
    }
}
