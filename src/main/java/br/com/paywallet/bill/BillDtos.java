package br.com.paywallet.bill;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import br.com.paywallet.ledger.Money;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;

public final class BillDtos {

    private BillDtos() {
    }

    public record LookupRequest(@NotBlank String code) {
    }

    /** {@code value} is only needed for open-amount bills; otherwise the amount due is paid. */
    public record PayBillRequest(
            @NotBlank String code,
            @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal value) {
    }

    public record BillQuoteResponse(BoletoCode.Kind kind, String barcode, String digitableLine, String bankCode,
                                    String beneficiaryName, String beneficiaryDocument, LocalDate dueDate,
                                    BigDecimal nominalValue, BigDecimal valueDue, BigDecimal minValue,
                                    BigDecimal maxValue, LocalDate paymentDeadline, boolean payable,
                                    String notPayableReason) {
    }

    public record BillPaymentResponse(UUID id, BillPayment.Status status, BoletoCode.Kind kind, String digitableLine,
                                      String beneficiaryName, String beneficiaryDocument, LocalDate dueDate,
                                      BigDecimal value, String authenticationCode, String failureReason,
                                      Instant createdAt, Instant settledAt) {

        static BillPaymentResponse from(BillPayment p) {
            return new BillPaymentResponse(p.getId(), p.getStatus(), p.getKind(), p.getDigitableLine(),
                    p.getBeneficiaryName(), p.getBeneficiaryDocument(), p.getDueDate(), Money.fromCents(p.getAmount()),
                    p.getAuthenticationCode(), p.getFailureReason(), p.getCreatedAt(), p.getSettledAt());
        }
    }

    public record PayResult(BillPaymentResponse payment, boolean replayed) {
    }
}
