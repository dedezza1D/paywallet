package br.com.paywallet.bill;

import java.util.UUID;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.bill.BillDtos.BillPaymentResponse;
import br.com.paywallet.bill.BillDtos.BillQuoteResponse;
import br.com.paywallet.bill.BillDtos.LookupRequest;
import br.com.paywallet.bill.BillDtos.PayBillRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/bills")
public class BillController {

    private final BillService bills;

    public BillController(BillService bills) {
        this.bills = bills;
    }

    /** Decodes the code and fetches beneficiary, amount due and deadline before the user confirms. */
    @PostMapping("/lookup")
    public BillQuoteResponse lookup(@Valid @RequestBody LookupRequest req) {
        return bills.lookup(req.code());
    }

    /** 202: the wallet is debited now and the bill is settled with the bank shortly after. */
    @PostMapping("/payments")
    public ResponseEntity<BillPaymentResponse> pay(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @Valid @RequestBody PayBillRequest req) {
        var result = bills.pay(userId(jwt), req, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.payment());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.payment());
    }

    @GetMapping("/payments")
    public Page<BillPaymentResponse> list(@AuthenticationPrincipal Jwt jwt,
                                          @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                          Pageable pageable) {
        return bills.list(userId(jwt), pageable);
    }

    @GetMapping("/payments/{id}")
    public BillPaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return bills.get(userId(jwt), id);
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
