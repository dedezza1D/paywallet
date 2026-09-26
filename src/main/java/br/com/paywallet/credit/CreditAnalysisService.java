package br.com.paywallet.credit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.user.User;
import br.com.paywallet.user.UserService;
import br.com.paywallet.user.UserType;

/**
 * Scores a customer by combining the credit bureau (60%) with behaviour on the platform (40%): account age,
 * inflows over the last 90 days, current balance and repayment history. Every decision is stored with the
 * reasons that shaped it and reused until it expires, so the bureau is not queried on every request.
 */
@Service
public class CreditAnalysisService {

    private static final int COMPONENT_MAX = 250;

    private final CreditAnalysisRepository analyses;
    private final CreditBureau bureau;
    private final UserService users;
    private final LedgerService ledger;
    private final JdbcTemplate jdbc;
    private final CreditProperties props;
    private final Clock clock;

    public CreditAnalysisService(CreditAnalysisRepository analyses, CreditBureau bureau, UserService users,
                                 LedgerService ledger, JdbcTemplate jdbc, CreditProperties props, Clock clock) {
        this.analyses = analyses;
        this.bureau = bureau;
        this.users = users;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** The latest decision while it is valid; otherwise a new analysis. */
    @Transactional
    public CreditAnalysis current(Long userId) {
        Instant now = clock.instant();
        return analyses.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .filter(a -> a.getExpiresAt().isAfter(now))
                .orElseGet(() -> analyze(userId));
    }

    private CreditAnalysis analyze(Long userId) {
        User user = users.get(userId);
        if (user.getType() != UserType.COMMON) {
            throw new BusinessException("Personal loans are available to individuals only");
        }
        var report = bureau.report(user.getDocument());
        List<String> reasons = new ArrayList<>();
        int internal = internalScore(user, reasons);
        int score = (int) Math.round(report.score() * 0.6 + internal * 0.4);

        CreditPolicy band = null;
        if (report.restricted()) {
            reasons.addFirst("Overdue debts registered at the credit bureau");
        } else if (hasOverdueInstallments(userId)) {
            reasons.addFirst("Overdue loan installments on this platform");
        } else {
            band = CreditPolicy.forScore(score).orElse(null);
            if (band == null) {
                reasons.addFirst("Score below the minimum for credit");
            }
        }
        long limit = band == null ? 0 : band.limitFor(inflowLast90Days(userId) / 3);
        Instant now = clock.instant();
        return analyses.save(new CreditAnalysis(userId, report.score(), internal, score, band, limit, reasons, now,
                now.plus(props.analysisValidity())));
    }

    private int internalScore(User user, List<String> reasons) {
        long ageDays = Duration.between(user.getCreatedAt(), clock.instant()).toDays();
        int age = (int) Math.min(COMPONENT_MAX, ageDays * COMPONENT_MAX / 180);
        if (ageDays < 30) {
            reasons.add("Account opened less than 30 days ago");
        }

        long inflow = inflowLast90Days(user.getId());
        int inflowPoints = (int) Math.min(COMPONENT_MAX, inflow * COMPONENT_MAX / 1_000_000);
        if (inflow < 100_000) {
            reasons.add("Low inflows in the last 90 days");
        }

        long balance = ledger.walletOf(user.getId()).getBalance();
        int balancePoints = (int) Math.min(COMPONENT_MAX, balance * COMPONENT_MAX / 500_000);

        var history = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE i.status = 'PAID') AS paid,
                       count(*) FILTER (WHERE i.status = 'PAID' AND i.late_charges > 0) AS paid_late
                  FROM loan_installments i JOIN loans l ON l.id = i.loan_id
                 WHERE l.user_id = ?
                """, user.getId());
        long paid = ((Number) history.get("paid")).longValue();
        long paidLate = ((Number) history.get("paid_late")).longValue();
        int historyPoints;
        if (paid == 0) {
            historyPoints = COMPONENT_MAX / 2;
            reasons.add("No repayment history on this platform");
        } else if (paidLate > 0) {
            historyPoints = 60;
            reasons.add("Installments paid late in the past");
        } else {
            historyPoints = paid >= 3 ? COMPONENT_MAX : COMPONENT_MAX * 3 / 4;
        }
        return age + inflowPoints + balancePoints + historyPoints;
    }

    /** Money received in the wallet over 90 days, excluding yield and loan disbursements. */
    long inflowLast90Days(Long userId) {
        Long inflow = jdbc.queryForObject("""
                SELECT coalesce(sum(p.amount), 0)
                  FROM postings p JOIN ledger_transactions t ON t.id = p.transaction_id
                 WHERE p.account_id = ? AND p.direction = 'CREDIT' AND p.created_at >= ?
                   AND t.type NOT IN ('YIELD_CREDIT', 'LOAN_DISBURSEMENT')
                """, Long.class, ledger.walletOf(userId).getId(),
                Timestamp.from(clock.instant().minus(Duration.ofDays(90))));
        return inflow == null ? 0 : inflow;
    }

    private boolean hasOverdueInstallments(Long userId) {
        Integer overdue = jdbc.queryForObject("""
                SELECT count(*) FROM loan_installments i JOIN loans l ON l.id = i.loan_id
                 WHERE l.user_id = ? AND i.status = 'OVERDUE'
                """, Integer.class, userId);
        return overdue != null && overdue > 0;
    }
}
