package br.com.paywallet.yield;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.user.User;
import br.com.paywallet.user.UserService;
import br.com.paywallet.user.UserType;
import br.com.paywallet.yield.YieldDtos.DailyYield;
import br.com.paywallet.yield.YieldDtos.RunResult;
import br.com.paywallet.yield.YieldDtos.YieldSummary;

/**
 * Credits individuals' wallets with a share of the CDI on every business day, based on the balance at the end
 * of that day. Each account is credited in its own transaction, keyed by account and date, so a run can be
 * interrupted and repeated safely.
 */
@Service
public class YieldService {

    private static final Logger log = LoggerFactory.getLogger(YieldService.class);

    private final CdiRateProvider cdi;
    private final LedgerService ledger;
    private final UserService users;
    private final BalanceCache balanceCache;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final YieldProperties props;
    private final Clock clock;

    public YieldService(CdiRateProvider cdi, LedgerService ledger, UserService users, BalanceCache balanceCache,
                        JdbcTemplate jdbc, TransactionTemplate transactions, YieldProperties props, Clock clock) {
        this.cdi = cdi;
        this.ledger = ledger;
        this.users = users;
        this.balanceCache = balanceCache;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.props = props;
        this.clock = clock;
    }

    private record EndOfDayBalance(UUID accountId, Long ownerId, long balance) {
    }

    /** Processes every business day since the last completed run, up to yesterday. */
    @Scheduled(cron = "0 0 8 * * *", zone = "America/Sao_Paulo")
    public void runPending() {
        if (!props.enabled()) {
            return;
        }
        LocalDate yesterday = LocalDate.now(clock.withZone(props.zone())).minusDays(1);
        LocalDate lastCompleted = jdbc.queryForObject(
                "SELECT max(reference_date) FROM yield_runs WHERE completed_at IS NOT NULL", LocalDate.class);
        LocalDate from = lastCompleted == null ? yesterday : lastCompleted.plusDays(1);
        LocalDate oldest = yesterday.minusDays(props.maxCatchUpDays());
        if (from.isBefore(oldest)) {
            log.warn("Yield job was down since {}; only days from {} are processed", from, oldest);
            from = oldest;
        }
        if (from.isAfter(yesterday)) {
            return;
        }
        cdi.dailyRates(from, yesterday).forEach(this::run);
    }

    /** Runs one business day with the rate the CDI source reports for it. */
    public RunResult runFor(LocalDate date) {
        BigDecimal rate = cdi.dailyRates(date, date).get(date);
        if (rate == null) {
            throw new BusinessException("%s is not a business day or its CDI is not published yet".formatted(date));
        }
        return run(date, rate);
    }

    private RunResult run(LocalDate date, BigDecimal rate) {
        var now = clock.instant();
        jdbc.update("""
                INSERT INTO yield_runs (reference_date, cdi_daily_rate, cdi_percentage, started_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (reference_date) DO NOTHING
                """, Date.valueOf(date), rate, props.cdiPercentage(), Timestamp.from(now));

        var endOfDay = Timestamp.from(date.plusDays(1).atStartOfDay(props.zone()).toInstant());
        List<EndOfDayBalance> balances = jdbc.query("""
                SELECT DISTINCT ON (p.account_id) p.account_id, a.owner_id, p.balance_after
                  FROM postings p
                  JOIN accounts a ON a.id = p.account_id
                  JOIN users u ON u.id = a.owner_id
                 WHERE a.type = 'USER_WALLET' AND u.type = ? AND p.created_at < ?
                 ORDER BY p.account_id, p.created_at DESC
                """,
                (rs, i) -> new EndOfDayBalance(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)),
                UserType.COMMON.name(), endOfDay);

        Map<UUID, BigDecimal> carries = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (account_id) account_id, carry
                  FROM yield_accruals WHERE reference_date < ?
                 ORDER BY account_id, reference_date DESC
                """, rs -> {
            carries.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
        }, Date.valueOf(date));

        for (EndOfDayBalance balance : balances) {
            if (balance.balance() > 0) {
                accrue(date, rate, balance, carries.getOrDefault(balance.accountId(), BigDecimal.ZERO));
            }
        }

        jdbc.update("""
                UPDATE yield_runs
                   SET accounts = (SELECT count(*) FROM yield_accruals WHERE reference_date = ?),
                       total_credited = (SELECT coalesce(sum(credited), 0) FROM yield_accruals WHERE reference_date = ?),
                       completed_at = ?
                 WHERE reference_date = ?
                """, Date.valueOf(date), Date.valueOf(date), Timestamp.from(clock.instant()), Date.valueOf(date));
        var result = jdbc.queryForObject("SELECT accounts, total_credited FROM yield_runs WHERE reference_date = ?",
                (rs, i) -> new RunResult(date, rate, props.cdiPercentage(), rs.getInt(1),
                        Money.fromCents(rs.getLong(2))), Date.valueOf(date));
        log.info("Yield for {}: {} accounts, R$ {} credited", date, result.accounts(), result.totalCredited());
        return result;
    }

    private void accrue(LocalDate date, BigDecimal rate, EndOfDayBalance balance, BigDecimal carryIn) {
        var accrual = YieldMath.accrue(balance.balance(), rate, props.cdiPercentage(), carryIn);
        try {
            transactions.executeWithoutResult(status -> {
                UUID ledgerTx = null;
                if (accrual.credited() > 0) {
                    ledgerTx = ledger.post(new PostCommand(LedgerTransactionType.YIELD_CREDIT,
                            "yield:%s:%s".formatted(balance.accountId(), date), "Yield " + date,
                            List.of(Leg.debit(AccountType.YIELD_ACCOUNT_ID, accrual.credited()),
                                    Leg.credit(balance.accountId(), accrual.credited())))).getId();
                }
                jdbc.update("""
                        INSERT INTO yield_accruals (account_id, reference_date, balance, accrued, credited, carry,
                                                    ledger_transaction_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, balance.accountId(), Date.valueOf(date), balance.balance(), accrual.accrued(),
                        accrual.credited(), accrual.carry(), ledgerTx, Timestamp.from(clock.instant()));
            });
        } catch (DataIntegrityViolationException e) {
            return; // already accrued by an earlier or concurrent run
        }
        if (accrual.credited() > 0) {
            balanceCache.evict(balance.ownerId());
        }
    }

    public YieldSummary summary(Long userId) {
        User user = users.get(userId);
        UUID accountId = ledger.walletOf(userId).getId();
        var latestRate = jdbc.query(
                "SELECT cdi_daily_rate FROM yield_runs WHERE completed_at IS NOT NULL ORDER BY reference_date DESC LIMIT 1",
                (rs, i) -> rs.getBigDecimal(1)).stream().findFirst().orElse(null);
        long total = jdbc.queryForObject("SELECT coalesce(sum(credited), 0) FROM yield_accruals WHERE account_id = ?",
                Long.class, accountId);
        LocalDate since = LocalDate.now(clock.withZone(props.zone())).minusDays(30);
        long last30 = jdbc.queryForObject("""
                SELECT coalesce(sum(credited), 0) FROM yield_accruals WHERE account_id = ? AND reference_date >= ?
                """, Long.class, accountId, Date.valueOf(since));
        List<DailyYield> history = jdbc.query("""
                SELECT reference_date, balance, credited FROM yield_accruals
                 WHERE account_id = ? ORDER BY reference_date DESC LIMIT 30
                """, (rs, i) -> new DailyYield(rs.getObject(1, LocalDate.class), Money.fromCents(rs.getLong(2)),
                Money.fromCents(rs.getLong(3))), accountId);
        return new YieldSummary(user.getType() == UserType.COMMON, props.cdiPercentage(), latestRate,
                latestRate == null ? null : YieldMath.annualized(latestRate, props.cdiPercentage()),
                Money.fromCents(total), Money.fromCents(last30), history);
    }
}
