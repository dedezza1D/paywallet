package br.com.paywallet.ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import br.com.paywallet.exception.NotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;

/**
 * The only writer of money. Every movement is a {@link LedgerTransaction} with two or more
 * {@link Posting} legs that sum to zero.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final PostingRepository postings;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    public LedgerService(AccountRepository accounts, LedgerTransactionRepository transactions,
                         PostingRepository postings, EntityManager em, JdbcTemplate jdbc, MeterRegistry meters) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.postings = postings;
        this.em = em;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    public record Leg(UUID accountId, Direction direction, long amount) {

        public static Leg debit(UUID accountId, long amount) {
            return new Leg(accountId, Direction.DEBIT, amount);
        }

        public static Leg credit(UUID accountId, long amount) {
            return new Leg(accountId, Direction.CREDIT, amount);
        }
    }

    public record PostCommand(LedgerTransactionType type, String idempotencyKey, String description, List<Leg> legs) {
    }

    public record Mismatch(UUID accountId, long snapshotBalance, long postingsBalance) {
    }

    /** {@code consistent}: every snapshot matches its postings and all balances sum to zero. */
    public record ReconciliationReport(boolean consistent, long systemTotal, List<Mismatch> mismatches) {
    }

    @Transactional
    public Account openUserWallet(Long userId) {
        var account = Account.userWallet(userId);
        em.persist(account);
        return account;
    }

    @Transactional(readOnly = true)
    public Account walletOf(Long userId) {
        return accounts.findByOwnerIdAndType(userId, AccountType.USER_WALLET)
                .orElseThrow(() -> new NotFoundException("Wallet of user %d not found".formatted(userId)));
    }

    /**
     * Posts a movement atomically. Accounts are always locked in id order so that crossing
     * movements (A to B and B to A) cannot deadlock.
     *
     * @throws br.com.paywallet.exception.InsufficientFundsException if an account would go negative
     * @throws org.springframework.dao.DataIntegrityViolationException if the idempotency key already exists
     * @throws IllegalArgumentException if the legs do not sum to zero
     */
    @Transactional
    public LedgerTransaction post(PostCommand cmd) {
        validate(cmd.legs());

        Map<UUID, Account> locked = new HashMap<>();
        cmd.legs().stream().map(Leg::accountId).distinct().sorted().forEach(id -> locked.put(id, lock(id)));

        // Postgres stores microseconds; truncating keeps the returned value identical to what a replay reads.
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Through the repository so a duplicate idempotency key surfaces as DataIntegrityViolationException here.
        var tx = transactions.saveAndFlush(
                new LedgerTransaction(cmd.type(), cmd.idempotencyKey(), cmd.description(), now));
        for (Leg leg : cmd.legs()) {
            var account = locked.get(leg.accountId());
            long balanceAfter = account.apply(leg.direction(), leg.amount());
            em.persist(new Posting(tx, account, leg.direction(), leg.amount(), balanceAfter, now));
        }
        countAfterCommit(cmd);
        return tx;
    }

    /**
     * SELECT ... FOR UPDATE that re-reads the row. A locking query would return the instance the caller's transaction
     * may have loaded earlier, and fail its version check if another movement committed in between.
     */
    private Account lock(UUID id) {
        var account = em.getReference(Account.class, id);
        try {
            em.refresh(account, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException e) {
            throw new NotFoundException("Account %s not found".formatted(id));
        }
        return account;
    }

    /** Movements by type and the money they moved, counted only once committed. */
    private void countAfterCommit(PostCommand cmd) {
        long amount = cmd.legs().stream().filter(l -> l.direction() == Direction.DEBIT).mapToLong(Leg::amount).sum();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meters.counter("paywallet.ledger.transactions", "type", cmd.type().name()).increment();
                Counter.builder("paywallet.ledger.amount").baseUnit("brl").tag("type", cmd.type().name())
                        .register(meters).increment(amount / 100.0);
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<LedgerTransaction> findByIdempotencyKey(String key) {
        return transactions.findByIdempotencyKey(key);
    }

    @Transactional(readOnly = true)
    public List<Posting> postingsOf(UUID transactionId) {
        return postings.findByTransactionId(transactionId);
    }

    @Transactional(readOnly = true)
    public Page<Posting> statement(UUID accountId, Pageable pageable) {
        return postings.findByAccountId(accountId, pageable);
    }

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        List<Mismatch> mismatches = jdbc.query("""
                SELECT a.id, a.balance,
                       COALESCE(SUM(CASE p.direction WHEN 'CREDIT' THEN p.amount ELSE -p.amount END), 0) AS computed
                  FROM accounts a
                  LEFT JOIN postings p ON p.account_id = a.id
                 GROUP BY a.id, a.balance
                HAVING a.balance <> COALESCE(SUM(CASE p.direction WHEN 'CREDIT' THEN p.amount ELSE -p.amount END), 0)
                """,
                (rs, i) -> new Mismatch(rs.getObject("id", UUID.class), rs.getLong("balance"), rs.getLong("computed")));
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM accounts", Long.class);
        long systemTotal = total == null ? 0 : total;
        return new ReconciliationReport(mismatches.isEmpty() && systemTotal == 0, systemTotal, mismatches);
    }

    private static void validate(List<Leg> legs) {
        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("A movement needs at least two legs");
        }
        long sum = 0;
        for (Leg leg : legs) {
            if (leg.amount() <= 0) {
                throw new IllegalArgumentException("Every leg amount must be positive");
            }
            sum = Math.addExact(sum, leg.direction().signed(leg.amount()));
        }
        if (sum != 0) {
            throw new IllegalArgumentException("Unbalanced movement: debits and credits differ by " + sum);
        }
    }
}
