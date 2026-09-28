package br.com.paywallet.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwt               access token issuing and validation
 * @param refreshTokenTtl   lifetime of an unused refresh token
 * @param loginMaxAttempts  consecutive failed logins before a temporary lock
 * @param loginLockDuration lock duration after too many failures
 * @param allowSelfDeposit  lets users simulate incoming money into their own wallet (development only)
 * @param codes             one-time codes sent by email for verification and password reset
 * @param pin               transaction PIN required to move money out
 * @param mfa               TOTP second factor
 * @param ipRateLimit       requests per minute and client IP accepted on the public authentication endpoints
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        Jwt jwt,
        Duration refreshTokenTtl,
        int loginMaxAttempts,
        Duration loginLockDuration,
        boolean allowSelfDeposit,
        Codes codes,
        Pin pin,
        Mfa mfa,
        int ipRateLimit,
        List<String> corsAllowedOrigins) {

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

    public record Pin(int maxAttempts, Duration lockDuration) {
    }

    /**
     * @param issuer       name shown in the authenticator app
     * @param challengeTtl time to enter the code after the password
     */
    public record Mfa(String issuer, Duration challengeTtl) {
    }
}
