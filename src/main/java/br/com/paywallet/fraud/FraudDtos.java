package br.com.paywallet.fraud;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class FraudDtos {

    private FraudDtos() {
    }

    public enum AlertStatus { OPEN, DISMISSED, CONFIRMED }

    public record AlertResponse(UUID id, Long userId, Channel channel, BigDecimal amount, String counterparty,
                                int score, Assessment.Decision decision, List<RiskRule> rules, AlertStatus status,
                                Long resolvedBy, String resolutionNote, Instant createdAt, Instant resolvedAt) {
    }

    /**
     * Confirming fraud blocks the customer's outflows unless {@code blockUser} is false; {@code watchlistCounterparty}
     * also refuses future payments to the same receiver.
     */
    public record ResolveAlertRequest(
            @NotNull AlertStatus status,
            @Size(max = 255) String note,
            Boolean blockUser,
            Boolean watchlistCounterparty) {
    }

    public record WatchlistEntry(
            @NotBlank @Size(max = 120) @Pattern(regexp = "(user|pix|doc|merchant):.+",
                    message = "must start with user:, pix:, doc: or merchant:") String value,
            @NotBlank @Size(max = 255) String reason) {
    }

    public record BlockRequest(@NotBlank @Size(max = 255) String reason) {
    }

    public record BlockedUser(Long userId, String reason, UUID alertId, Long blockedBy, Instant blockedAt) {
    }
}
