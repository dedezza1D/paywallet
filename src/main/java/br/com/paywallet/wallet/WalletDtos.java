package br.com.paywallet.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.paywallet.feed.Visibility;
import br.com.paywallet.ledger.Direction;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.ledger.Posting;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class WalletDtos {

    private WalletDtos() {
    }

    public record TransferRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value,
            @NotNull Long payee,
            @Size(max = 140) String message,
            Visibility visibility) {
    }

    public record DepositRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value) {
    }

    public record TransferResponse(UUID transactionId, Long payer, Long payee, BigDecimal value, Instant createdAt) {
    }

    public record DepositResponse(UUID transactionId, Long userId, BigDecimal value, Instant createdAt) {
    }

    /** {@code replayed}: the Idempotency-Key was already processed and no money moved again. */
    public record TransferResult(TransferResponse response, boolean replayed) {
    }

    public record BalanceResponse(Long userId, UUID accountId, BigDecimal balance) {
    }

    public record LimitResponse(BigDecimal dailyLimit, BigDecimal usedToday, BigDecimal remainingToday) {
    }

    public record StatementEntry(UUID transactionId, LedgerTransactionType type, Direction direction,
                                 BigDecimal value, BigDecimal balanceAfter, Instant createdAt) {

        static StatementEntry from(Posting p) {
            return new StatementEntry(p.getTransaction().getId(), p.getTransaction().getType(), p.getDirection(),
                    Money.fromCents(p.getAmount()), Money.fromCents(p.getBalanceAfter()), p.getCreatedAt());
        }
    }
}
