package br.com.paywallet.wallet;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.ConflictException;
import br.com.paywallet.exception.InsufficientFundsException;
import br.com.paywallet.exception.TransferNotAuthorizedException;
import br.com.paywallet.external.AuthorizationClient;
import br.com.paywallet.feed.Visibility;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.hotdata.DailyLimitService;
import br.com.paywallet.hotdata.IdempotencyGuard;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.Direction;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransaction;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.messaging.Topics;
import br.com.paywallet.outbox.OutboxWriter;
import br.com.paywallet.user.User;
import br.com.paywallet.user.UserService;
import br.com.paywallet.wallet.WalletDtos.BalanceResponse;
import br.com.paywallet.wallet.WalletDtos.DepositResponse;
import br.com.paywallet.wallet.WalletDtos.LimitResponse;
import br.com.paywallet.wallet.WalletDtos.StatementEntry;
import br.com.paywallet.wallet.WalletDtos.TransferRequest;
import br.com.paywallet.wallet.WalletDtos.TransferResponse;
import br.com.paywallet.wallet.WalletDtos.TransferResult;

/**
 * Orchestrates a transfer across the data stores:
 * <ol>
 *   <li>Redis: idempotency lock and daily limit reservation;</li>
 *   <li>external authorizer, called outside any database transaction so no row lock waits on HTTP;</li>
 *   <li>Postgres, in one transaction: double-entry movement with both accounts locked, plus the
 *       outbox event that later feeds Kafka;</li>
 *   <li>after commit: balance cache eviction.</li>
 * </ol>
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final UserService users;
    private final LedgerService ledger;
    private final AuthorizationClient authorizer;
    private final IdempotencyGuard idempotency;
    private final DailyLimitService limits;
    private final BalanceCache balanceCache;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;

    public WalletService(UserService users, LedgerService ledger, AuthorizationClient authorizer,
                         IdempotencyGuard idempotency, DailyLimitService limits, BalanceCache balanceCache,
                         OutboxWriter outbox, TransactionTemplate transactions) {
        this.users = users;
        this.ledger = ledger;
        this.authorizer = authorizer;
        this.idempotency = idempotency;
        this.limits = limits;
        this.balanceCache = balanceCache;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /** @param payerId the authenticated user; never taken from the request body */
    public TransferResult transfer(Long payerId, TransferRequest req, String idempotencyKey) {
        if (payerId.equals(req.payee())) {
            throw new BusinessException("Cannot transfer to yourself");
        }
        long amount = Money.toCents(req.value());
        // Scoped per payer so two users sending the same key never collide.
        String key = "p2p:%d:%s".formatted(payerId, idempotencyKey);

        var previous = ledger.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            return replay(previous.get(), req, amount);
        }

        String token = idempotency.tryAcquire(key).orElseThrow(() ->
                new ConflictException("A transfer with this Idempotency-Key is already in progress"));
        try {
            // Another request may have completed between the lookup above and acquiring the lock.
            previous = ledger.findByIdempotencyKey(key);
            if (previous.isPresent()) {
                return replay(previous.get(), req, amount);
            }
            return execute(payerId, req, amount, key);
        } finally {
            idempotency.release(key, token);
        }
    }

    private TransferResult execute(Long payerId, TransferRequest req, long amount, String key) {
        User payer = users.get(payerId);
        User payee = users.get(req.payee());
        if (!payer.getType().canSendMoney()) {
            throw new BusinessException("Merchants cannot send transfers");
        }
        var payerWallet = ledger.walletOf(payer.getId());
        var payeeWallet = ledger.walletOf(payee.getId());

        // Unlocked pre-check only to fail fast; the authoritative check happens in the ledger under lock.
        if (payerWallet.getBalance() < amount) {
            throw new InsufficientFundsException();
        }

        limits.reserve(payer.getId(), amount);
        LedgerTransaction tx;
        try {
            if (!authorizer.isAuthorized()) {
                throw new TransferNotAuthorizedException();
            }
            tx = transactions.execute(status -> {
                var posted = ledger.post(new PostCommand(LedgerTransactionType.P2P_TRANSFER, key,
                        "P2P %d -> %d".formatted(payer.getId(), payee.getId()),
                        List.of(Leg.debit(payerWallet.getId(), amount), Leg.credit(payeeWallet.getId(), amount))));
                outbox.append(Topics.TRANSFERS_COMPLETED, payer.getId().toString(), TransferCompletedEvent.TYPE,
                        new TransferCompletedEvent(posted.getId(), payer.getId(), payer.getFullName(),
                                payee.getId(), payee.getFullName(), payee.getEmail(), amount, req.message(),
                                req.visibility() == null ? Visibility.PRIVATE : req.visibility(),
                                posted.getCreatedAt()));
                return posted;
            });
        } catch (DataIntegrityViolationException e) {
            releaseLimit(payer.getId(), amount);
            // Race between instances with the same key (e.g. Redis restarted): the database UNIQUE decided.
            return ledger.findByIdempotencyKey(key).map(t -> replay(t, req, amount)).orElseThrow(() -> e);
        } catch (RuntimeException e) {
            releaseLimit(payer.getId(), amount);
            throw e;
        }

        balanceCache.evict(payer.getId());
        balanceCache.evict(payee.getId());
        return new TransferResult(new TransferResponse(tx.getId(), payer.getId(), payee.getId(),
                Money.fromCents(amount), tx.getCreatedAt()), false);
    }

    /** Returns the original result; rejects a key reused with a different payload. */
    private TransferResult replay(LedgerTransaction tx, TransferRequest req, long amount) {
        var legs = ledger.postingsOf(tx.getId());
        var debit = legs.stream().filter(p -> p.getDirection() == Direction.DEBIT).findFirst().orElseThrow();
        var credit = legs.stream().filter(p -> p.getDirection() == Direction.CREDIT).findFirst().orElseThrow();
        Long payee = credit.getAccount().getOwnerId();
        if (!req.payee().equals(payee) || debit.getAmount() != amount) {
            throw new BusinessException("Idempotency-Key already used for a different transfer");
        }
        return new TransferResult(new TransferResponse(tx.getId(), debit.getAccount().getOwnerId(), payee,
                Money.fromCents(debit.getAmount()), tx.getCreatedAt()), true);
    }

    /**
     * Money entering from outside (simulates Pix-in/boleto), balanced against the CASH_IN system account.
     * Idempotent by key: repeating the call never credits twice.
     */
    public DepositResponse deposit(Long userId, BigDecimal value, String idempotencyKey) {
        long amount = Money.toCents(value);
        String key = "cashin:%d:%s".formatted(userId, idempotencyKey);
        var wallet = ledger.walletOf(users.get(userId).getId());

        LedgerTransaction tx = ledger.findByIdempotencyKey(key).orElse(null);
        if (tx == null) {
            try {
                tx = ledger.post(new PostCommand(LedgerTransactionType.CASH_IN, key, "Deposit",
                        List.of(Leg.debit(AccountType.CASH_IN_ACCOUNT_ID, amount), Leg.credit(wallet.getId(), amount))));
            } catch (DataIntegrityViolationException e) {
                tx = ledger.findByIdempotencyKey(key).orElseThrow(() -> e);
            }
        }
        balanceCache.evict(userId);
        long credited = ledger.postingsOf(tx.getId()).stream()
                .filter(p -> p.getDirection() == Direction.CREDIT).mapToLong(p -> p.getAmount()).sum();
        return new DepositResponse(tx.getId(), userId, Money.fromCents(credited), tx.getCreatedAt());
    }

    public BalanceResponse balance(Long userId) {
        var wallet = ledger.walletOf(userId);
        var cached = balanceCache.get(userId);
        long cents;
        if (cached.isPresent()) {
            cents = cached.getAsLong();
        } else {
            cents = wallet.getBalance();
            balanceCache.put(userId, cents);
        }
        return new BalanceResponse(userId, wallet.getId(), Money.fromCents(cents));
    }

    public Page<StatementEntry> statement(Long userId, Pageable pageable) {
        var wallet = ledger.walletOf(userId);
        return ledger.statement(wallet.getId(), pageable).map(StatementEntry::from);
    }

    public LimitResponse limits(Long userId) {
        users.get(userId);
        var limit = limits.current(userId);
        return new LimitResponse(Money.fromCents(limit.limitCents()), Money.fromCents(limit.usedCents()),
                Money.fromCents(limit.remainingCents()));
    }

    private void releaseLimit(Long userId, long amount) {
        try {
            limits.release(userId, amount);
        } catch (DataAccessException e) {
            // Fails safe: the limit stays consumed until the day rolls over.
            log.error("Failed to release limit reservation for user {}: {}", userId, e.getMessage());
        }
    }
}
