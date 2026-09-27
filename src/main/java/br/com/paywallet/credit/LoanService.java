package br.com.paywallet.credit;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.credit.CreditDtos.CreditAnalysisResponse;
import br.com.paywallet.credit.CreditDtos.InstallmentPaymentResponse;
import br.com.paywallet.credit.CreditDtos.LoanQuoteResponse;
import br.com.paywallet.credit.CreditDtos.LoanRequest;
import br.com.paywallet.credit.CreditDtos.LoanResponse;
import br.com.paywallet.credit.CreditDtos.LoanResult;
import br.com.paywallet.credit.CreditDtos.PrepaymentQuote;
import br.com.paywallet.credit.CreditDtos.ScheduleEntry;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.InsufficientFundsException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.messaging.Topics;
import br.com.paywallet.outbox.OutboxWriter;
import br.com.paywallet.user.UserService;
import jakarta.persistence.EntityManager;

/**
 * Personal loans on top of the ledger. Disbursement moves the financed principal into LOAN_PRINCIPAL, the amount
 * into the wallet and the IOF into TAX_PAYABLE; each installment payment returns principal to LOAN_PRINCIPAL and
 * books interest and late charges as INTEREST_INCOME.
 */
@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    private final CreditAnalysisService analyses;
    private final LoanRepository loans;
    private final LedgerService ledger;
    private final UserService users;
    private final BalanceCache balanceCache;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final CreditProperties props;
    private final Clock clock;

    public LoanService(CreditAnalysisService analyses, LoanRepository loans, LedgerService ledger, UserService users,
                       BalanceCache balanceCache, OutboxWriter outbox, TransactionTemplate transactions,
                       JdbcTemplate jdbc, EntityManager em, CreditProperties props, Clock clock) {
        this.analyses = analyses;
        this.loans = loans;
        this.ledger = ledger;
        this.users = users;
        this.balanceCache = balanceCache;
        this.outbox = outbox;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.em = em;
        this.props = props;
        this.clock = clock;
    }

    private record InstallmentRow(int number, LocalDate dueDate, long amount, long principal, long interest,
                                  String status, Long lateCharges, Long discount, Instant paidAt) {
    }

    public record CollectionResult(LocalDate date, int paid, int overdue) {
    }

    public CreditAnalysisResponse analysis(Long userId) {
        return CreditAnalysisResponse.from(analyses.current(userId), outstanding(userId));
    }

    public LoanQuoteResponse simulate(Long userId, LoanRequest req) {
        return LoanQuoteResponse.from(quote(userId, req, analyses.current(userId)));
    }

    public LoanResult contract(Long userId, LoanRequest req, String idempotencyKey) {
        String key = "loan:%d:%s".formatted(userId, idempotencyKey);
        var previous = loans.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            return replay(previous.get(), req);
        }
        Loan loan;
        try {
            loan = transactions.execute(status -> {
                // One contract at a time per customer, so concurrent requests cannot exceed the limit together.
                jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> { }, userId);
                var analysis = analyses.current(userId);
                var quote = quote(userId, req, analysis);
                var legs = new ArrayList<Leg>();
                legs.add(Leg.debit(AccountType.LOAN_PRINCIPAL_ACCOUNT_ID, quote.financed()));
                legs.add(Leg.credit(ledger.walletOf(userId).getId(), quote.amount()));
                if (quote.iof() > 0) {
                    legs.add(Leg.credit(AccountType.TAX_PAYABLE_ACCOUNT_ID, quote.iof()));
                }
                var tx = ledger.post(new PostCommand(LedgerTransactionType.LOAN_DISBURSEMENT, key, "Loan disbursement",
                        legs));
                var created = new Loan(userId, analysis.getId(), quote, key, tx.getId(), clock.instant());
                em.persist(created);
                em.flush();
                for (var installment : quote.schedule()) {
                    jdbc.update("""
                            INSERT INTO loan_installments (loan_id, number, due_date, amount, principal, interest, status)
                            VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                            """, created.getId(), installment.number(), Date.valueOf(installment.dueDate()),
                            installment.amount(), installment.principal(), installment.interest());
                }
                return created;
            });
        } catch (DataIntegrityViolationException e) {
            return loans.findByIdempotencyKey(key).map(l -> replay(l, req)).orElseThrow(() -> e);
        }
        balanceCache.evict(userId);
        return new LoanResult(response(loan), false);
    }

    @Transactional(readOnly = true)
    public List<LoanResponse> list(Long userId) {
        return loans.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public LoanResponse get(Long userId, UUID loanId) {
        return response(loans.findByIdAndUserId(loanId, userId).orElseThrow(() -> new NotFoundException("Loan not found")));
    }

    /** Customer-initiated payment of the oldest unpaid installment, with late charges if it is overdue. */
    public InstallmentPaymentResponse pay(Long userId, UUID loanId, int number) {
        var loan = loans.findByIdAndUserId(loanId, userId).orElseThrow(() -> new NotFoundException("Loan not found"));
        Integer oldest = jdbc.queryForObject(
                "SELECT min(number) FROM loan_installments WHERE loan_id = ? AND status <> 'PAID'", Integer.class,
                loan.getId());
        if (oldest != null && oldest != number) {
            throw new BusinessException("Pay installment %d first".formatted(oldest));
        }
        return payInstallment(loan.getId(), number, today());
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "America/Sao_Paulo")
    public void scheduledCollection() {
        if (props.collectionEnabled()) {
            collect(today());
        }
    }

    /**
     * Debits every installment due up to {@code date} from the borrower's wallet, oldest first. When the balance
     * is short, the installment becomes overdue (the borrower is notified once) and later ones wait.
     */
    public CollectionResult collect(LocalDate date) {
        record Due(UUID loanId, int number) {
        }
        List<Due> due = jdbc.query("""
                SELECT i.loan_id, i.number FROM loan_installments i JOIN loans l ON l.id = i.loan_id
                 WHERE l.status = 'ACTIVE' AND i.status <> 'PAID' AND i.due_date <= ?
                 ORDER BY i.loan_id, i.number
                """, (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getInt(2)), Date.valueOf(date));
        Set<UUID> blocked = new HashSet<>();
        int paid = 0;
        int overdue = 0;
        for (Due installment : due) {
            if (blocked.contains(installment.loanId())) {
                continue;
            }
            try {
                payInstallment(installment.loanId(), installment.number(), date);
                paid++;
            } catch (InsufficientFundsException e) {
                blocked.add(installment.loanId());
                if (markOverdue(installment.loanId(), installment.number())) {
                    overdue++;
                }
            }
        }
        log.info("Installment collection for {}: {} paid, {} became overdue", date, paid, overdue);
        return new CollectionResult(date, paid, overdue);
    }

    private InstallmentPaymentResponse payInstallment(UUID loanId, int number, LocalDate paymentDate) {
        var result = transactions.execute(status -> {
            var loan = loans.lockById(loanId).orElseThrow();
            var row = installment(loanId, number);
            if ("PAID".equals(row.status())) {
                return paymentResponse(loan, row);
            }
            long daysLate = Math.max(0, ChronoUnit.DAYS.between(row.dueDate(), paymentDate));
            long late = LoanMath.lateCharges(row.amount(), daysLate, props.lateFinePercent(),
                    props.lateMonthlyInterestPercent());
            var tx = ledger.post(new PostCommand(LedgerTransactionType.LOAN_INSTALLMENT_PAYMENT,
                    "loan-installment:%s:%d".formatted(loanId, number), "Loan installment %d".formatted(number),
                    List.of(Leg.debit(ledger.walletOf(loan.getUserId()).getId(), row.amount() + late),
                            Leg.credit(AccountType.LOAN_PRINCIPAL_ACCOUNT_ID, row.principal()),
                            Leg.credit(AccountType.INTEREST_INCOME_ACCOUNT_ID, row.interest() + late))));
            Instant now = clock.instant();
            jdbc.update("""
                    UPDATE loan_installments SET status = 'PAID', late_charges = ?, paid_at = ?, ledger_transaction_id = ?
                     WHERE loan_id = ? AND number = ?
                    """, late, Timestamp.from(now), tx.getId(), loanId, number);
            Integer unpaid = jdbc.queryForObject(
                    "SELECT count(*) FROM loan_installments WHERE loan_id = ? AND status <> 'PAID'", Integer.class, loanId);
            if (unpaid != null && unpaid == 0) {
                loan.payOff(now);
            }
            return paymentResponse(loan, installment(loanId, number));
        });
        balanceCache.evict(loans.findById(loanId).orElseThrow().getUserId());
        return result;
    }

    private boolean markOverdue(UUID loanId, int number) {
        Boolean marked = transactions.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE loan_installments SET status = 'OVERDUE' WHERE loan_id = ? AND number = ? AND status = 'PENDING'
                    """, loanId, number);
            if (updated == 0) {
                return false;
            }
            var loan = loans.findById(loanId).orElseThrow();
            var user = users.get(loan.getUserId());
            var row = installment(loanId, number);
            outbox.append(Topics.LOAN_INSTALLMENT_OVERDUE, user.getId().toString(), InstallmentOverdueEvent.TYPE,
                    new InstallmentOverdueEvent(loanId, user.getId(), user.getEmail(), number, row.amount(), row.dueDate()));
            return true;
        });
        return Boolean.TRUE.equals(marked);
    }

    private LoanMath.Quote quote(Long userId, LoanRequest req, CreditAnalysis analysis) {
        if (!analysis.isApproved()) {
            List<String> reasons = analysis.reasonList();
            throw new BusinessException("Credit not approved" + (reasons.isEmpty() ? "" : ": " + reasons.getFirst()));
        }
        if (req.installments() < props.minInstallments() || req.installments() > props.maxInstallments()) {
            throw new BusinessException("Installments must be between %d and %d"
                    .formatted(props.minInstallments(), props.maxInstallments()));
        }
        long amount = Money.toCents(req.value());
        if (amount < Money.toCents(props.minAmount())) {
            throw new BusinessException("Minimum loan amount is R$ " + props.minAmount());
        }
        long available = analysis.getCreditLimit() - outstanding(userId);
        if (amount > available) {
            throw new BusinessException("Amount exceeds the available credit limit of R$ " + Money.fromCents(Math.max(0, available)));
        }
        LocalDate today = today();
        return LoanMath.quote(amount, req.installments(), analysis.getMonthlyRate(), today, today.plusMonths(1),
                props.iofFlatPercent(), props.iofDailyPercent());
    }

    private LoanResult replay(Loan loan, LoanRequest req) {
        if (loan.getAmount() != Money.toCents(req.value()) || loan.getInstallments() != req.installments()) {
            throw new BusinessException("Idempotency-Key already used for a different loan");
        }
        return new LoanResult(response(loan), true);
    }

    private long outstanding(Long userId) {
        Long outstanding = jdbc.queryForObject("""
                SELECT coalesce(sum(i.principal), 0) FROM loan_installments i JOIN loans l ON l.id = i.loan_id
                 WHERE l.user_id = ? AND i.status <> 'PAID'
                """, Long.class, userId);
        return outstanding == null ? 0 : outstanding;
    }

    public PrepaymentQuote prepaymentQuote(Long userId, UUID loanId, Integer count) {
        var loan = loans.findByIdAndUserId(loanId, userId).orElseThrow(() -> new NotFoundException("Loan not found"));
        return prepayment(loan, count, today()).quote(loan.getId());
    }

    /**
     * Pays the next {@code count} unpaid installments now, or all of them to pay off the loan. Installments not yet
     * due are discounted to present value; overdue ones carry their late charges.
     */
    public LoanResult prepay(Long userId, UUID loanId, Integer count, String idempotencyKey) {
        String key = "loan-prepayment:%d:%s".formatted(userId, idempotencyKey);
        if (ledger.findByIdempotencyKey(key).isPresent()) {
            return new LoanResult(get(userId, loanId), true);
        }
        try {
            transactions.executeWithoutResult(status -> {
                var loan = loans.lockById(loanId).filter(l -> l.getUserId().equals(userId))
                        .orElseThrow(() -> new NotFoundException("Loan not found"));
                var plan = prepayment(loan, count, today());
                long principal = plan.principal();
                var legs = new ArrayList<Leg>();
                legs.add(Leg.debit(ledger.walletOf(userId).getId(), plan.total()));
                legs.add(Leg.credit(AccountType.LOAN_PRINCIPAL_ACCOUNT_ID, principal));
                if (plan.total() > principal) {
                    legs.add(Leg.credit(AccountType.INTEREST_INCOME_ACCOUNT_ID, plan.total() - principal));
                }
                var tx = ledger.post(new PostCommand(LedgerTransactionType.LOAN_PREPAYMENT, key,
                        "Loan prepayment of %d installments".formatted(plan.lines().size()), legs));
                Instant now = clock.instant();
                for (var line : plan.lines()) {
                    jdbc.update("""
                            UPDATE loan_installments
                               SET status = 'PAID', late_charges = ?, discount = ?, paid_at = ?,
                                   ledger_transaction_id = ?
                             WHERE loan_id = ? AND number = ?
                            """, line.late(), line.discount(), Timestamp.from(now), tx.getId(), loanId, line.number());
                }
                Integer unpaid = jdbc.queryForObject(
                        "SELECT count(*) FROM loan_installments WHERE loan_id = ? AND status <> 'PAID'", Integer.class,
                        loanId);
                if (unpaid != null && unpaid == 0) {
                    loan.payOff(now);
                }
            });
        } catch (DataIntegrityViolationException e) {
            if (ledger.findByIdempotencyKey(key).isPresent()) {
                return new LoanResult(get(userId, loanId), true);
            }
            throw e;
        }
        balanceCache.evict(userId);
        return new LoanResult(get(userId, loanId), false);
    }

    private record PrepaymentLine(int number, long amount, long discount, long late) {
    }

    private record Prepayment(List<PrepaymentLine> lines, long principal) {

        long nominal() {
            return lines.stream().mapToLong(PrepaymentLine::amount).sum();
        }

        long discount() {
            return lines.stream().mapToLong(PrepaymentLine::discount).sum();
        }

        long late() {
            return lines.stream().mapToLong(PrepaymentLine::late).sum();
        }

        long total() {
            return nominal() - discount() + late();
        }

        PrepaymentQuote quote(UUID loanId) {
            return new PrepaymentQuote(loanId, lines.stream().map(PrepaymentLine::number).toList(),
                    Money.fromCents(nominal()), Money.fromCents(discount()), Money.fromCents(late()),
                    Money.fromCents(total()));
        }
    }

    /**
     * The next installments in order. A discount never exceeds the installment's interest, so the principal they
     * cover is always paid.
     */
    private Prepayment prepayment(Loan loan, Integer count, LocalDate today) {
        var unpaid = installments(loan.getId()).stream().filter(r -> !"PAID".equals(r.status())).toList();
        if (unpaid.isEmpty()) {
            throw new BusinessException("Loan already paid off");
        }
        int n = count == null ? unpaid.size() : count;
        if (n < 1 || n > unpaid.size()) {
            throw new BusinessException("Choose between 1 and %d installments".formatted(unpaid.size()));
        }
        var lines = new ArrayList<PrepaymentLine>();
        long principal = 0;
        for (var row : unpaid.subList(0, n)) {
            long days = ChronoUnit.DAYS.between(today, row.dueDate());
            long late = days < 0 ? LoanMath.lateCharges(row.amount(), -days, props.lateFinePercent(),
                    props.lateMonthlyInterestPercent()) : 0;
            long discount = Math.min(row.interest(),
                    row.amount() - LoanMath.presentValue(row.amount(), loan.getMonthlyRate(), days));
            lines.add(new PrepaymentLine(row.number(), row.amount(), discount, late));
            principal += row.principal();
        }
        return new Prepayment(lines, principal);
    }

    private InstallmentRow installment(UUID loanId, int number) {
        return installments(loanId).stream().filter(i -> i.number() == number).findFirst()
                .orElseThrow(() -> new NotFoundException("Installment not found"));
    }

    private List<InstallmentRow> installments(UUID loanId) {
        return jdbc.query("""
                SELECT number, due_date, amount, principal, interest, status, late_charges, discount, paid_at
                  FROM loan_installments WHERE loan_id = ? ORDER BY number
                """, (rs, i) -> new InstallmentRow(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getString(6), (Long) rs.getObject(7), (Long) rs.getObject(8),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()), loanId);
    }

    private LoanResponse response(Loan loan) {
        var rows = installments(loan.getId());
        long outstanding = rows.stream().filter(r -> !"PAID".equals(r.status())).mapToLong(InstallmentRow::principal).sum();
        return new LoanResponse(loan.getId(), loan.getStatus(), Money.fromCents(loan.getAmount()),
                Money.fromCents(loan.getIof()), Money.fromCents(loan.getFinanced()),
                CreditDtos.percent(loan.getMonthlyRate()), CreditDtos.percent(loan.getCetAnnual()),
                loan.getInstallments(), Money.fromCents(outstanding), loan.getCreatedAt(), loan.getPaidOffAt(),
                rows.stream().map(r -> new ScheduleEntry(r.number(), r.dueDate(), Money.fromCents(r.amount()),
                        Money.fromCents(r.principal()), Money.fromCents(r.interest()), r.status(),
                        r.lateCharges() == null ? null : Money.fromCents(r.lateCharges()),
                        r.discount() == null ? null : Money.fromCents(r.discount()), r.paidAt())).toList());
    }

    private InstallmentPaymentResponse paymentResponse(Loan loan, InstallmentRow row) {
        long late = row.lateCharges() == null ? 0 : row.lateCharges();
        return new InstallmentPaymentResponse(loan.getId(), row.number(), Money.fromCents(row.amount()),
                Money.fromCents(late), Money.fromCents(row.amount() + late), row.paidAt(), loan.getStatus());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(props.zone()));
    }
}
