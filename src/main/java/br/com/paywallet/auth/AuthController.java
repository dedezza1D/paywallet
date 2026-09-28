package br.com.paywallet.auth;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import br.com.paywallet.auth.AuthDtos.ChangePasswordRequest;
import br.com.paywallet.auth.AuthDtos.CloseAccountRequest;
import br.com.paywallet.auth.AuthDtos.ClosureCheck;
import br.com.paywallet.auth.AuthDtos.DisableMfaRequest;
import br.com.paywallet.auth.AuthDtos.EmailRequest;
import br.com.paywallet.auth.AuthDtos.LoginRequest;
import br.com.paywallet.auth.AuthDtos.MfaCodeRequest;
import br.com.paywallet.auth.AuthDtos.MfaLoginRequest;
import br.com.paywallet.auth.AuthDtos.MfaSetupResponse;
import br.com.paywallet.auth.AuthDtos.RecoveryCodesResponse;
import br.com.paywallet.auth.AuthDtos.RefreshRequest;
import br.com.paywallet.auth.AuthDtos.ResetPasswordRequest;
import br.com.paywallet.auth.AuthDtos.SetPinRequest;
import br.com.paywallet.auth.AuthDtos.TokenResponse;
import br.com.paywallet.auth.AuthDtos.VerifyEmailRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
public class AuthController {

    private final AuthService auth;
    private final AccountService accounts;
    private final MfaService mfa;
    private final TransactionPinService pins;
    private final AccountClosureService closures;
    private final Map<String, Object> publicJwks;

    public AuthController(AuthService auth, AccountService accounts, MfaService mfa, TransactionPinService pins,
                          AccountClosureService closures, RSAKey jwtSigningKey) {
        this.auth = auth;
        this.accounts = accounts;
        this.mfa = mfa;
        this.pins = pins;
        this.closures = closures;
        this.publicJwks = new JWKSet(jwtSigningKey.toPublicJWK()).toJSONObject();
    }

    /** Session tokens, or {@code mfaRequired} with a token to finish at {@code /auth/login/mfa}. */
    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        var result = auth.login(req.email(), req.password(), device(request));
        return noStore(result.tokens() != null ? result.tokens() : result.challenge());
    }

    @PostMapping("/auth/login/mfa")
    public ResponseEntity<?> loginWithCode(@Valid @RequestBody MfaLoginRequest req, HttpServletRequest request) {
        return noStore(auth.completeMfa(req.mfaToken(), req.code(), device(request)));
    }

    @PostMapping("/auth/mfa/setup")
    public ResponseEntity<?> setupMfa(@AuthenticationPrincipal Jwt jwt) {
        var setup = mfa.setup(userId(jwt));
        return noStore(new MfaSetupResponse(setup.secret(), setup.otpauthUri()));
    }

    @PostMapping("/auth/mfa/enable")
    public ResponseEntity<?> enableMfa(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MfaCodeRequest req) {
        return noStore(new RecoveryCodesResponse(mfa.enable(userId(jwt), req.code())));
    }

    @PostMapping("/auth/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableMfa(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DisableMfaRequest req) {
        mfa.disable(userId(jwt), req.password(), req.code());
    }

    @PostMapping("/auth/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SetPinRequest req) {
        pins.change(userId(jwt), req.password(), req.pin());
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return noStore(auth.refresh(req.refreshToken()));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
    }

    @PostMapping("/auth/email/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        accounts.verifyEmail(req.email(), req.code());
    }

    @PostMapping("/auth/email/verification-code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerificationCode(@Valid @RequestBody EmailRequest req) {
        accounts.resendVerificationCode(req.email());
    }

    @PostMapping("/auth/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody EmailRequest req) {
        accounts.forgotPassword(req.email());
    }

    @PostMapping("/auth/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        accounts.resetPassword(req.email(), req.code(), req.newPassword());
    }

    @PostMapping("/auth/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
        accounts.changePassword(userId(jwt), req.currentPassword(), req.newPassword());
    }

    @GetMapping("/auth/account/closure")
    public ClosureCheck closureCheck(@AuthenticationPrincipal Jwt jwt) {
        var blockers = closures.blockers(userId(jwt));
        return new ClosureCheck(blockers.isEmpty(), blockers);
    }

    /** Closes the account for good: records are kept as the law requires, but it cannot be used again. */
    @PostMapping("/auth/account/closure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeAccount(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CloseAccountRequest req) {
        closures.close(userId(jwt), req.password(), req.code());
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return publicJwks;
    }

    private static DeviceAlerts.Device device(HttpServletRequest request) {
        return new DeviceAlerts.Device(request.getHeader("X-Device-Id"), request.getHeader("User-Agent"),
                request.getRemoteAddr());
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }
}
