package br.com.paywallet.auth;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import br.com.paywallet.auth.AuthDtos.LoginRequest;
import br.com.paywallet.auth.AuthDtos.RefreshRequest;
import br.com.paywallet.auth.AuthDtos.TokenResponse;
import jakarta.validation.Valid;

@RestController
public class AuthController {

    private final AuthService auth;
    private final Map<String, Object> publicJwks;

    public AuthController(AuthService auth, RSAKey jwtSigningKey) {
        this.auth = auth;
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

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return publicJwks;
    }

    private static ResponseEntity<TokenResponse> noStore(TokenResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }
}
