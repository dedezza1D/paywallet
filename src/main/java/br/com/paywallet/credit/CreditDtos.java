package br.com.paywallet.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import br.com.paywallet.ledger.Money;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Rates are shown in percent (3.49 means 3.49% a month). */
public final class CreditDtos {

    private CreditDtos() {
    }

    public record CreditAnalysisResponse(UUID id, boolean approved, int score, String riskBand, BigDecimal creditLimit,
                                         BigDecimal available, BigDecimal monthlyRate, List<String> reasons,
                                         Instant createdAt, Instant expiresAt) {

        static CreditAnalysisResponse from(CreditAnalysis a, long outstandingCents) {
            return new CreditAnalysisResponse(a.getId(), a.isApproved(), a.getScore(), a.getRiskBand(),
                    Money.fromCents(a.getCreditLimit()),
                    Money.fromCents(Math.max(0, a.getCreditLimit() - outstandingCents)),
                    percent(a.getMonthlyRate()), a.reasonList(), a.getCreatedAt(), a.getExpiresAt());
        }
    }

    public record LoanRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value,
            @NotNull Integer installments) {
    }

    /** {@code discount}: interest not charged because the installment was paid early. */
    public record ScheduleEntry(int number, LocalDate dueDate, BigDecimal amount, BigDecimal principal,
                                BigDecimal interest, String status, BigDecimal lateCharges, BigDecimal discount,
                                Instant paidAt) {
    }

    /** What the customer sees before signing: the regulation requires the CET alongside the nominal rate. */
    public record LoanQuoteResponse(BigDecimal value, int installments, BigDecimal monthlyRate, BigDecimal iof,
                                    BigDecimal financed, BigDecimal installmentAmount, BigDecimal totalPayable,
                                    BigDecimal cetMonthly, BigDecimal cetAnnual, List<ScheduleEntry> schedule) {

        static LoanQuoteResponse from(LoanMath.Quote q) {
            return new LoanQuoteResponse(Money.fromCents(q.amount()), q.schedule().size(), percent(q.monthlyRate()),
                    Money.fromCents(q.iof()), Money.fromCents(q.financed()),
                    Money.fromCents(q.schedule().getFirst().amount()), Money.fromCents(q.totalPayable()),
                    percent(q.cetMonthly()), percent(q.cetAnnual()),
                    q.schedule().stream().map(i -> new ScheduleEntry(i.number(), i.dueDate(),
                            Money.fromCents(i.amount()), Money.fromCents(i.principal()),
                            Money.fromCents(i.interest()), null, null, null, null)).toList());
        }
    }

    public record LoanResponse(UUID id, Loan.Status status, BigDecimal value, BigDecimal iof, BigDecimal financed,
                               BigDecimal monthlyRate, BigDecimal cetAnnual, int installments,
                               BigDecimal outstandingPrincipal, Instant createdAt, Instant paidOffAt,
                               List<ScheduleEntry> schedule) {
    }

    public record LoanResult(LoanResponse loan, boolean replayed) {
    }

    public record InstallmentPaymentResponse(UUID loanId, int number, BigDecimal amount, BigDecimal lateCharges,
                                             BigDecimal total, Instant paidAt, Loan.Status loanStatus) {
    }

    /** Without {@code installments}, pays off the whole loan. */
    public record PrepaymentRequest(@Min(1) Integer installments) {
    }

    public record PrepaymentQuote(UUID loanId, List<Integer> installments, BigDecimal nominal, BigDecimal discount,
                                  BigDecimal lateCharges, BigDecimal total) {
    }

    static BigDecimal percent(BigDecimal fraction) {
        return fraction == null ? null : fraction.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }
}
