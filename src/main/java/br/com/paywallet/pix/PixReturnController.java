package br.com.paywallet.pix;

import java.util.List;
import java.util.UUID;

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

import br.com.paywallet.ledger.Money;
import br.com.paywallet.pix.PixReturnDtos.FraudClaimRequest;
import br.com.paywallet.pix.PixReturnDtos.FraudClaimResponse;
import br.com.paywallet.pix.PixReturnDtos.FraudClaimStatus;
import br.com.paywallet.pix.PixReturnDtos.PixReturnResponse;
import br.com.paywallet.pix.PixReturnDtos.ResolveClaimRequest;
import br.com.paywallet.pix.PixReturnDtos.ReturnPixRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
public class PixReturnController {

    private final PixReturnService returns;
    private final PixFraudClaimService claims;

    public PixReturnController(PixReturnService returns, PixFraudClaimService claims) {
        this.returns = returns;
        this.claims = claims;
    }

    /** By the receiver of the Pix. 201 when settled at once, 202 while a return to another institution is pending. */
    @PostMapping("/pix/payments/{endToEndId}/returns")
    public ResponseEntity<PixReturnResponse> returnPix(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String endToEndId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @Valid @RequestBody(required = false) ReturnPixRequest req) {
        Long value = req == null || req.value() == null ? null : Money.toCents(req.value());
        var result = returns.request(userId(jwt), endToEndId, value, req == null ? null : req.reason(),
                idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.pixReturn());
        }
        var status = result.pixReturn().status() == PixReturn.Status.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.pixReturn());
    }

    @GetMapping("/pix/payments/{endToEndId}/returns")
    public List<PixReturnResponse> returns(@AuthenticationPrincipal Jwt jwt, @PathVariable String endToEndId) {
        return returns.list(userId(jwt), endToEndId);
    }

    /** By the payer, who reports the Pix as fraud (MED). */
    @PostMapping("/pix/payments/{endToEndId}/fraud-claims")
    public ResponseEntity<FraudClaimResponse> claim(@AuthenticationPrincipal Jwt jwt, @PathVariable String endToEndId,
                                                    @Valid @RequestBody FraudClaimRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claims.file(userId(jwt), endToEndId, req.description()));
    }

    @GetMapping("/pix/fraud-claims")
    public List<FraudClaimResponse> myClaims(@AuthenticationPrincipal Jwt jwt) {
        return claims.mine(userId(jwt));
    }

    @GetMapping("/admin/pix/fraud-claims")
    public List<FraudClaimResponse> allClaims(@RequestParam(required = false) FraudClaimStatus status) {
        return claims.list(status);
    }

    @PostMapping("/admin/pix/fraud-claims/{id}/resolution")
    public FraudClaimResponse resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                      @Valid @RequestBody ResolveClaimRequest req) {
        return claims.resolve(id, req, userId(jwt));
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
