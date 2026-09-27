package br.com.paywallet.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 6-digit one-time codes. Issuing a code voids the previous ones of the same purpose; a code expires, allows a few
 * wrong guesses and works once. Stored as HMAC(pepper, user, purpose, code): with only a million possible codes, a
 * plain hash would be reversed at once by anyone reading the table.
 */
@Component
class AccountCodes {

    enum Purpose { EMAIL_VERIFICATION, PASSWORD_RESET }

    private static final Logger log = LoggerFactory.getLogger(AccountCodes.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final SecurityProperties.Codes props;
    private final Clock clock;
    private final byte[] pepper;

    AccountCodes(JdbcTemplate jdbc, SecurityProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props.codes();
        this.clock = clock;
        if (this.props.pepper() == null || this.props.pepper().isBlank()) {
            log.warn("app.security.codes.pepper is not set: using an ephemeral key. Codes sent before a restart "
                    + "stop working");
            this.pepper = new byte[32];
            RANDOM.nextBytes(this.pepper);
        } else {
            this.pepper = this.props.pepper().getBytes(StandardCharsets.UTF_8);
        }
    }

    @Transactional
    String issue(Long userId, Purpose purpose) {
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE auth_codes SET consumed_at = ? WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL
                """, Timestamp.from(now), userId, purpose.name());
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        jdbc.update("""
                INSERT INTO auth_codes (id, user_id, purpose, code_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, purpose.name(), hash(userId, purpose, code),
                Timestamp.from(now.plus(props.ttl())), Timestamp.from(now));
        return code;
    }

    /**
     * Consumes the code when it matches; counts a wrong guess otherwise. In its own transaction, so a failed attempt
     * is recorded even though the caller's request fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean consume(Long userId, Purpose purpose, String code) {
        Instant now = clock.instant();
        record Active(UUID id, String hash, int attempts) {
        }
        var active = jdbc.query("""
                SELECT id, code_hash, attempts FROM auth_codes
                 WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > ?
                 ORDER BY created_at DESC LIMIT 1 FOR UPDATE
                """, (rs, i) -> new Active(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)),
                userId, purpose.name(), Timestamp.from(now)).stream().findFirst();
        if (active.isEmpty() || active.get().attempts() >= props.maxAttempts()) {
            return false;
        }
        boolean matches = MessageDigest.isEqual(active.get().hash().getBytes(StandardCharsets.US_ASCII),
                hash(userId, purpose, code).getBytes(StandardCharsets.US_ASCII));
        if (matches) {
            jdbc.update("UPDATE auth_codes SET consumed_at = ? WHERE id = ?", Timestamp.from(now), active.get().id());
        } else {
            jdbc.update("UPDATE auth_codes SET attempts = attempts + 1 WHERE id = ?", active.get().id());
        }
        return matches;
    }

    private String hash(Long userId, Purpose purpose, String code) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((userId + ":" + purpose + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
