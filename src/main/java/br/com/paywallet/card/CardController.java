package br.com.paywallet.card;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

import br.com.paywallet.card.CardDtos.AuthorizationResponse;
import br.com.paywallet.card.CardDtos.CardResponse;
import br.com.paywallet.card.CardDtos.ClosingResult;
import br.com.paywallet.card.CardDtos.DisputeRequest;
import br.com.paywallet.card.CardDtos.DisputeResponse;
import br.com.paywallet.card.CardDtos.FinanceStatementRequest;
import br.com.paywallet.card.CardDtos.InstallmentOption;
import br.com.paywallet.card.CardDtos.IssueCardRequest;
import br.com.paywallet.card.CardDtos.StatementPaymentRequest;
import br.com.paywallet.card.CardDtos.StatementResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
public class CardController {

    private final CardService cards;
    private final CardStatementService statements;
    private final CardRefundService refunds;

    public CardController(CardService cards, CardStatementService statements, CardRefundService refunds) {
        this.cards = cards;
        this.statements = statements;
        this.refunds = refunds;
    }

    @PostMapping("/cards")
    public ResponseEntity<CardResponse> issue(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody IssueCardRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cards.issue(userId(jwt), req));
    }

    @GetMapping("/cards")
    public List<CardResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return cards.list(userId(jwt));
    }

    @GetMapping("/cards/{id}")
    public CardResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return cards.get(userId(jwt), id);
    }

    @PostMapping("/cards/{id}/block")
    public CardResponse block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return cards.block(userId(jwt), id);
    }

    @PostMapping("/cards/{id}/unblock")
    public CardResponse unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return cards.unblock(userId(jwt), id);
    }

    @PostMapping("/cards/{id}/cancel")
    public CardResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return cards.cancel(userId(jwt), id);
    }

    @GetMapping("/cards/{id}/transactions")
    public Page<AuthorizationResponse> transactions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                    @PageableDefault(size = 20) Pageable pageable) {
        return cards.transactions(userId(jwt), id, pageable);
    }

    @PostMapping("/cards/{id}/transactions/{authorizationId}/disputes")
    public ResponseEntity<DisputeResponse> dispute(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                   @PathVariable String authorizationId,
                                                   @Valid @RequestBody DisputeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(refunds.openDispute(userId(jwt), id, authorizationId, req));
    }

    @GetMapping("/cards/{id}/disputes")
    public List<DisputeResponse> disputes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return refunds.disputes(userId(jwt), id);
    }

    @GetMapping("/cards/{id}/statements")
    public List<StatementResponse> statements(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return statements.list(userId(jwt), id);
    }

    @GetMapping("/cards/{id}/statements/{statementId}")
    public StatementResponse statement(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @PathVariable UUID statementId) {
        return statements.get(userId(jwt), id, statementId);
    }

    @GetMapping("/cards/{id}/statements/{statementId}/installment-options")
    public List<InstallmentOption> installmentOptions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                      @PathVariable UUID statementId) {
        return statements.installmentOptions(userId(jwt), id, statementId);
    }

    @PostMapping("/cards/{id}/statements/{statementId}/installment-plan")
    public StatementResponse finance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @PathVariable UUID statementId,
                                     @Valid @RequestBody FinanceStatementRequest req) {
        return statements.finance(userId(jwt), id, statementId, req.installments());
    }

    @PostMapping("/cards/{id}/statements/{statementId}/payment")
    public ResponseEntity<StatementResponse> pay(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID statementId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @Valid @RequestBody(required = false) StatementPaymentRequest req) {
        var result = statements.pay(userId(jwt), id, statementId, req == null ? null : req.value(), idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.statement());
        }
        return ResponseEntity.ok(result.statement());
    }

    /** Operations: close the statements due on a date (normally done by the daily job). */
    @PostMapping("/admin/cards/statements/close")
    public ClosingResult close(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return statements.close(date);
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
