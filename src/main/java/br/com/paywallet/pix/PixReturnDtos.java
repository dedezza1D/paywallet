package br.com.paywallet.pix;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.paywallet.ledger.Money;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PixReturnDtos {

    private PixReturnDtos() {
    }

    /** Without a value, returns everything not yet returned; the reason defaults to MD06 (customer request). */
    public record ReturnPixRequest(
            @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value,
            PixReturn.Reason reason) {
    }

    public record PixReturnResponse(String returnId, String originalEndToEndId, BigDecimal value,
                                    PixReturn.Reason reason, PixReturn.Status status, String failureReason,
                                    Instant createdAt, Instant settledAt) {

        static PixReturnResponse from(PixReturn r) {
            return new PixReturnResponse(r.getReturnId(), r.getOriginalEndToEndId(),
                    Money.fromCents(r.getAmount()), r.getReason(), r.getStatus(),
                    r.getFailureReason(), r.getCreatedAt(), r.getSettledAt());
        }
    }

    public record ReturnResult(PixReturnResponse pixReturn, boolean replayed) {
    }

    public record FraudClaimRequest(@NotBlank @Size(max = 500) String description) {
    }

    public record FraudClaimResponse(UUID id, String endToEndId, Long claimantUserId, Long receiverUserId,
                                     String description, BigDecimal blocked, BigDecimal returned,
                                     FraudClaimStatus status, Instant createdAt, Instant resolvedAt) {
    }

    public enum FraudClaimStatus { OPEN, ACCEPTED, REJECTED }

    /** Accepting returns the frozen money and, unless {@code blockReceiver} is false, blocks the receiver. */
    public record ResolveClaimRequest(boolean accepted, Boolean blockReceiver) {
    }
}
