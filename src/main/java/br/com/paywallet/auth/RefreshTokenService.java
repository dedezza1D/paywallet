package br.com.paywallet.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.exception.InvalidRefreshTokenException;
import jakarta.persistence.EntityManager;

/**
 * Rotating refresh tokens: each use invalidates the presented token and issues a new one in the same family.
 * If an already-rotated token shows up again, someone copied it, so the whole family is revoked. This logs
 * out both the attacker and the legitimate session, which must log in again.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final EntityManager em;
    private final SecurityProperties props;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository repository, EntityManager em,
                               SecurityProperties props, Clock clock) {
        this.repository = repository;
        this.em = em;
        this.props = props;
        this.clock = clock;
    }

    public record IssuedRefreshToken(String value, Instant expiresAt) {
    }

    public record Rotation(Long userId, IssuedRefreshToken next) {
    }

    /** Starts a session (login) with a new family. */
    @Transactional
    public IssuedRefreshToken startFamily(Long userId) {
        return issue(userId, UUID.randomUUID());
    }

    /** Exchanges a valid refresh token for a new one. Any problem yields the same generic 401. */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        var token = repository.findByHashForUpdate(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (token.getRevokedAt() != null) {
            throw new InvalidRefreshTokenException();
        }
        if (token.getUsedAt() != null) {
            int revoked = repository.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected (user {}): family {} revoked ({} tokens)",
                    token.getUserId(), token.getFamilyId(), revoked);
            throw new InvalidRefreshTokenException();
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }

        token.markUsed(now);
        return new Rotation(token.getUserId(), issue(token.getUserId(), token.getFamilyId()));
    }

    /** Logout ends the whole session (every token in the family). Idempotent. */
    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken))
                .ifPresent(t -> repository.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    private IssuedRefreshToken issue(Long userId, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.refreshTokenTtl());
        em.persist(new RefreshToken(userId, familyId, hash(value), now, expiresAt));
        return new IssuedRefreshToken(value, expiresAt);
    }

    private static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
