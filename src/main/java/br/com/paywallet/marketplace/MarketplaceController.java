package br.com.paywallet.marketplace;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.marketplace.MarketplaceDtos.CashbackSummary;
import br.com.paywallet.marketplace.MarketplaceDtos.OrderResponse;
import br.com.paywallet.marketplace.MarketplaceDtos.ProductResponse;
import br.com.paywallet.marketplace.MarketplaceDtos.PurchaseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
public class MarketplaceController {

    private final MarketplaceService marketplace;

    public MarketplaceController(MarketplaceService marketplace) {
        this.marketplace = marketplace;
    }

    @GetMapping("/marketplace/products")
    public List<ProductResponse> products(@RequestParam(required = false) Product.Category category) {
        return marketplace.catalog(category);
    }

    /** Accepted as PENDING: the product is delivered asynchronously, then the order becomes COMPLETED or FAILED. */
    @PostMapping("/marketplace/orders")
    public ResponseEntity<OrderResponse> purchase(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @Valid @RequestBody PurchaseRequest req) {
        var result = marketplace.purchase(userId(jwt), req, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.order());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.order());
    }

    @GetMapping("/marketplace/orders")
    public Page<OrderResponse> orders(@AuthenticationPrincipal Jwt jwt, @PageableDefault(size = 20) Pageable pageable) {
        return marketplace.list(userId(jwt), pageable);
    }

    /** The only place the gift card code is shown. */
    @GetMapping("/marketplace/orders/{id}")
    public OrderResponse order(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return marketplace.get(userId(jwt), id);
    }

    @GetMapping("/cashback")
    public CashbackSummary cashback(@AuthenticationPrincipal Jwt jwt) {
        return marketplace.cashback(userId(jwt));
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
