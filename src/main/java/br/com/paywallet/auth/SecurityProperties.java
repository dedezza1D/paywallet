package br.com.paywallet.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwt               access token issuing and validation
 * @param refreshTokenTtl   lifetime of an unused refresh token
 * @param loginMaxAttempts  consecutive failed logins before a temporary lock
 * @param loginLockDuration lock duration after too many failures
 * @param allowSelfDeposit  lets users simulate incoming money into their own wallet (development only)
 * @param codes             one-time codes sent by email for verification and password reset
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        Jwt jwt,
        Duration refreshTokenTtl,
        int loginMaxAttempts,
        Duration loginLockDuration,
        boolean allowSelfDeposit,
        Codes codes) {

    /**
     * @param privateKey RSA PKCS#8 PEM. Empty = ephemeral key generated at startup
     *                   (tokens die on every restart; unacceptable in production)
     */
    public record Jwt(String issuer, String audience, Duration accessTokenTtl, String privateKey) {
    }

    /**
     * @param pepper HMAC key for stored codes. Empty = ephemeral key generated at startup (codes sent before a
     *               restart stop working; development only)
     */
    public record Codes(Duration ttl, int maxAttempts, Duration resendCooldown, int maxSendsPerHour, String pepper) {
    }
}
