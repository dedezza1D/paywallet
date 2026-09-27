package br.com.paywallet.credit;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.auth.RequiresTransactionPin;
import br.com.paywallet.credit.CreditDtos.CreditAnalysisResponse;
import br.com.paywallet.credit.CreditDtos.InstallmentPaymentResponse;
import br.com.paywallet.credit.CreditDtos.LoanQuoteResponse;
import br.com.paywallet.credit.CreditDtos.LoanRequest;
import br.com.paywallet.credit.CreditDtos.LoanResponse;
import br.com.paywallet.credit.CreditDtos.PrepaymentQuote;
import br.com.paywallet.credit.CreditDtos.PrepaymentRequest;
import br.com.paywallet.credit.LoanService.CollectionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@RestController
public class LoanController {

    private final LoanService loans;

    public LoanController(LoanService loans) {
        this.loans = loans;
    }

    /** Current credit decision, running a new analysis when there is none or it expired. */
    @GetMapping("/credit/analysis")
    public CreditAnalysisResponse analysis(@AuthenticationPrincipal Jwt jwt) {
        return loans.analysis(userId(jwt));
    }

    @PostMapping("/loans/simulations")
    public LoanQuoteResponse simulate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LoanRequest req) {
        return loans.simulate(userId(jwt), req);
    }

    /** Signs the loan and disburses it into the wallet. */
    @PostMapping("/loans")
    public ResponseEntity<LoanResponse> contract(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @Valid @RequestBody LoanRequest req) {
        var result = loans.contract(userId(jwt), req, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.loan());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.loan());
    }

    @GetMapping("/loans")
    public List<LoanResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return loans.list(userId(jwt));
    }

    @GetMapping("/loans/{id}")
    public LoanResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return loans.get(userId(jwt), id);
    }

    @RequiresTransactionPin
    @PostMapping("/loans/{id}/installments/{number}/payment")
    public InstallmentPaymentResponse pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @PathVariable int number) {
        return loans.pay(userId(jwt), id, number);
    }

    @GetMapping("/loans/{id}/prepayment")
    public PrepaymentQuote prepaymentQuote(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @RequestParam(required = false) @Min(1) Integer installments) {
        return loans.prepaymentQuote(userId(jwt), id, installments);
    }

    @RequiresTransactionPin
    @PostMapping("/loans/{id}/prepayment")
    public ResponseEntity<LoanResponse> prepay(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @Valid @RequestBody(required = false) PrepaymentRequest req) {
        var result = loans.prepay(userId(jwt), id, req == null ? null : req.installments(), idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.loan());
        }
        return ResponseEntity.ok(result.loan());
    }

    /** Operations: run the installment collection for a date (normally done by the daily job). */
    @PostMapping("/admin/loans/collections")
    public CollectionResult collect(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return loans.collect(date);
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
