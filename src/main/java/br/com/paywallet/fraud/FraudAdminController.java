package br.com.paywallet.fraud;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.fraud.FraudDtos.AlertResponse;
import br.com.paywallet.fraud.FraudDtos.AlertStatus;
import br.com.paywallet.fraud.FraudDtos.BlockRequest;
import br.com.paywallet.fraud.FraudDtos.BlockedUser;
import br.com.paywallet.fraud.FraudDtos.ResolveAlertRequest;
import br.com.paywallet.fraud.FraudDtos.WatchlistEntry;
import jakarta.validation.Valid;

/** Admin only, through the {@code /admin/**} rule of the security configuration. */
@RestController
@RequestMapping("/admin/fraud")
public class FraudAdminController {

    private final FraudAdminService admin;

    public FraudAdminController(FraudAdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/alerts")
    public List<AlertResponse> alerts(@RequestParam(required = false) AlertStatus status,
                                      @RequestParam(required = false) Long userId) {
        return admin.alerts(status, userId);
    }

    @PostMapping("/alerts/{id}/resolution")
    public AlertResponse resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody ResolveAlertRequest req) {
        return admin.resolve(id, req, analyst(jwt));
    }

    @GetMapping("/blocked-users/{userId}")
    public BlockedUser blocked(@PathVariable Long userId) {
        return admin.blocked(userId);
    }

    @PostMapping("/blocked-users/{userId}")
    public BlockedUser block(@AuthenticationPrincipal Jwt jwt, @PathVariable Long userId,
                             @Valid @RequestBody BlockRequest req) {
        return admin.block(userId, req.reason(), analyst(jwt));
    }

    @DeleteMapping("/blocked-users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable Long userId) {
        admin.unblock(userId);
    }

    @GetMapping("/watchlist")
    public List<WatchlistEntry> watchlist() {
        return admin.watchlist();
    }

    @PostMapping("/watchlist")
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistEntry addToWatchlist(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WatchlistEntry entry) {
        return admin.addToWatchlist(entry, analyst(jwt));
    }

    @DeleteMapping("/watchlist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromWatchlist(@RequestParam String value) {
        admin.removeFromWatchlist(value);
    }

    private static Long analyst(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
