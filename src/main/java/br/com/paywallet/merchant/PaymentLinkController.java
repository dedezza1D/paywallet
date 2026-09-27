package br.com.paywallet.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.auth.RequiresTransactionPin;
import br.com.paywallet.merchant.ChargeDtos.PaymentReceipt;
import br.com.paywallet.merchant.ChargeDtos.PublicChargeResponse;

/** Target of the shareable payment link: anyone may view the charge, a logged-in user may pay it. */
@RestController
public class PaymentLinkController {

    private final ChargeService charges;

    public PaymentLinkController(ChargeService charges) {
        this.charges = charges;
    }

    @GetMapping("/pay/{token}")
    public PublicChargeResponse view(@PathVariable String token) {
        return charges.publicView(token);
    }

    @RequiresTransactionPin
    @PostMapping("/pay/{token}")
    public ResponseEntity<PaymentReceipt> pay(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        var result = charges.payWithWallet(Long.valueOf(jwt.getSubject()), token);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.receipt());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.receipt());
    }
}
