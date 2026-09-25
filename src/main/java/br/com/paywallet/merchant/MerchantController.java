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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.merchant.ChargeDtos.ChargeResponse;
import br.com.paywallet.merchant.ChargeDtos.CreateChargeRequest;
import br.com.paywallet.merchant.ChargeDtos.Dashboard;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/merchant")
public class MerchantController {

    private final ChargeService charges;
    private final MerchantDashboardService dashboard;

    public MerchantController(ChargeService charges, MerchantDashboardService dashboard) {
        this.charges = charges;
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
