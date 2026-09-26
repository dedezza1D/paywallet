package br.com.paywallet.card;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CardDtos {

    private CardDtos() {
    }

    /** {@code closingDay} applies to credit cards only; defaults to the 5th. */
    public record IssueCardRequest(@NotNull Card.Type type, @Min(1) @Max(28) Integer closingDay) {
    }

    public record CardResponse(UUID id, Card.Type type, Card.Status status, String brand, String last4, int expMonth,
                               int expYear, BigDecimal creditLimit, BigDecimal availableLimit, Integer closingDay,
                               Instant createdAt) {
    }

    public record AuthorizationResponse(String id, UUID cardId, BigDecimal amount, String merchantName, String mcc,
                                        int installments, CardAuthorization.Status status, String responseCode,
                                        String declineReason, BigDecimal clearedAmount, Instant createdAt,
                                        Instant updatedAt) {
    }

    public record ChargeResponse(String description, BigDecimal amount, int installment, int installments,
                                 LocalDate billingDate) {
    }

    public record StatementResponse(UUID id, UUID cardId, LocalDate closingDate, LocalDate dueDate, BigDecimal total,
                                    BigDecimal minimumPayment, BigDecimal paid, BigDecimal remaining,
                                    CardStatement.Status status, List<ChargeResponse> charges) {
    }

    /** Without a value, pays everything still owed on the statement. */
    public record StatementPaymentRequest(@DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value) {
    }

    public record StatementPaymentResult(StatementResponse statement, boolean replayed) {
    }

    public record ClosingResult(LocalDate date, int closed, int carried) {
    }

    /** Sent by the processor when a card is used; the response decides the purchase in real time. */
    public record AuthorizationRequest(
            @NotBlank @Size(max = 64) String authorizationId,
            @NotBlank @Size(max = 64) String cardToken,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 100) String merchantName,
            @Pattern(regexp = "\\d{4}") String mcc,
            @Min(1) @Max(12) Integer installments) {
    }

    /** {@code responseCode} follows ISO 8583: 00 approved, 51 insufficient funds, 54 expired, 57 not permitted, 14 invalid card. */
    public record AuthorizationDecision(String authorizationId, boolean approved, String responseCode, String reason) {
    }

    /** The final amount of an approved purchase, which may be lower than the authorized one. */
    public record ClearingRequest(
            @NotBlank @Size(max = 64) String authorizationId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    /** Cancels an approved purchase that was never cleared. */
    public record ReversalRequest(@NotBlank @Size(max = 64) String authorizationId) {
    }

    public record RefundRequest(
            @NotBlank @Size(max = 64) String refundId,
            @NotBlank @Size(max = 64) String authorizationId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount) {
    }

    public record RefundResponse(String id, String authorizationId, String kind, BigDecimal amount,
                                 Instant createdAt) {
    }

    public enum DisputeReason { NOT_RECOGNIZED, NOT_RECEIVED, DUPLICATE, WRONG_AMOUNT, CANCELLED }

    public enum DisputeStatus { OPEN, WON, LOST }

    public record DisputeRequest(@NotNull DisputeReason reason, @Size(max = 500) String description) {
    }

    public record DisputeResponse(UUID id, String authorizationId, DisputeReason reason, String description,
                                  BigDecimal amount, DisputeStatus status, Instant createdAt, Instant resolvedAt) {
    }

    public record DisputeOutcomeRequest(
            @NotBlank @Size(max = 64) String authorizationId,
            @NotNull DisputeStatus outcome) {
    }
}
