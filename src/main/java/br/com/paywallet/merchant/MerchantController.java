package br.com.paywallet.merchant;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.ledger.Money;
import br.com.paywallet.merchant.ChargeDtos.ChargeResponse;
import br.com.paywallet.merchant.ChargeDtos.CreateChargeRequest;
import br.com.paywallet.merchant.ChargeDtos.Dashboard;
import br.com.paywallet.merchant.ChargeDtos.RefundRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/merchant")
public class MerchantController {

    private final ChargeService charges;
    private final ChargeRefundService refunds;
    private final MerchantDashboardService dashboard;

    public MerchantController(ChargeService charges, ChargeRefundService refunds, MerchantDashboardService dashboard) {
        this.charges = charges;
        this.refunds = refunds;
        this.dashboard = dashboard;
    }

    /** 201 for a new charge, 200 when the reference matches an existing one. */
    @PostMapping("/charges")
    public ResponseEntity<ChargeResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody CreateChargeRequest req) {
        var result = charges.create(merchantId(jwt), req);
        return ResponseEntity.status(result.existing() ? HttpStatus.OK : HttpStatus.CREATED).body(result.charge());
    }

    @GetMapping("/charges")
    public Page<ChargeResponse> list(@AuthenticationPrincipal Jwt jwt,
                                     @RequestParam(required = false) Charge.Status status,
                                     @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                     Pageable pageable) {
        return charges.list(merchantId(jwt), status, pageable);
    }

    @GetMapping("/charges/{id}")
    public ChargeResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return charges.get(merchantId(jwt), id);
    }

    @PostMapping("/charges/{id}/cancel")
    public ChargeResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return charges.cancel(merchantId(jwt), id);
    }

    @PostMapping("/charges/{id}/refunds")
    public ResponseEntity<ChargeResponse> refund(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @Valid @RequestBody(required = false) RefundRequest req) {
        Long value = req == null || req.value() == null ? null : Money.toCents(req.value());
        var result = refunds.refund(merchantId(jwt), id, value, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.charge());
        }
        return ResponseEntity.ok(result.charge());
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboard.dashboard(merchantId(jwt), from, to);
    }

    private static Long merchantId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
