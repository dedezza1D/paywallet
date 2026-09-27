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
import br.com.paywallet.auth.AuthDtos.EmailRequest;
import br.com.paywallet.auth.AuthDtos.LoginRequest;
import br.com.paywallet.auth.AuthDtos.RefreshRequest;
import br.com.paywallet.auth.AuthDtos.ResetPasswordRequest;
import br.com.paywallet.auth.AuthDtos.TokenResponse;
import br.com.paywallet.auth.AuthDtos.VerifyEmailRequest;
import jakarta.validation.Valid;

@RestController
public class AuthController {

    private final AuthService auth;
    private final AccountService accounts;
    private final Map<String, Object> publicJwks;

    public AuthController(AuthService auth, AccountService accounts, RSAKey jwtSigningKey) {
        this.auth = auth;
        this.accounts = accounts;
        this.publicJwks = new JWKSet(jwtSigningKey.toPublicJWK()).toJSONObject();
    }

    @PostMapping("/auth/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return noStore(auth.login(req.email(), req.password()));
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
        accounts.changePassword(Long.valueOf(jwt.getSubject()), req.currentPassword(), req.newPassword());
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return publicJwks;
    }

    private static ResponseEntity<TokenResponse> noStore(TokenResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }
}
