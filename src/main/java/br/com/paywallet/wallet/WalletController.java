package br.com.paywallet.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.auth.RequiresTransactionPin;
import br.com.paywallet.wallet.WalletDtos.BalanceResponse;
import br.com.paywallet.wallet.WalletDtos.DepositRequest;
import br.com.paywallet.wallet.WalletDtos.DepositResponse;
import br.com.paywallet.wallet.WalletDtos.LimitResponse;
import br.com.paywallet.wallet.WalletDtos.StatementEntry;
import br.com.paywallet.wallet.WalletDtos.TransferRequest;
import br.com.paywallet.wallet.WalletDtos.TransferResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
public class WalletController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final String KEY_FORMAT = "[A-Za-z0-9_-]{8,100}";

    private final WalletService service;

    public WalletController(WalletService service) {
        this.service = service;
    }

    /**
     * The payer is always the token owner; nobody can pay on behalf of someone else.
     * Clients send one Idempotency-Key (e.g. a UUID) per payment intent and resend it on retries.
     * First execution: 201. Same key again: 200 with the Idempotent-Replayed header.
     */
    @RequiresTransactionPin
    @PostMapping("/transfer")
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(IDEMPOTENCY_HEADER) @Pattern(regexp = KEY_FORMAT) String idempotencyKey,
            @Valid @RequestBody TransferRequest req) {
        var result = service.transfer(Long.valueOf(jwt.getSubject()), req, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(result.response());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
    }

    /** Simulates incoming money (Pix-in/boleto) until those integrations exist. */
    @PostMapping("/users/{id}/deposit")
    public DepositResponse deposit(
            @PathVariable Long id,
            @RequestHeader(IDEMPOTENCY_HEADER) @Pattern(regexp = KEY_FORMAT) String idempotencyKey,
            @Valid @RequestBody DepositRequest req) {
        return service.deposit(id, req.value(), idempotencyKey);
    }

    @GetMapping("/users/{id}/balance")
    public BalanceResponse balance(@PathVariable Long id) {
        return service.balance(id);
    }

    @GetMapping("/users/{id}/statement")
    public Page<StatementEntry> statement(
            @PathVariable Long id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.statement(id, pageable);
    }

    @GetMapping("/users/{id}/limits")
    public LimitResponse limits(@PathVariable Long id) {
        return service.limits(id);
    }
}
