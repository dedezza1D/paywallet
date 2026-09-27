package br.com.paywallet.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.InvalidCredentialsException;
import br.com.paywallet.user.User;
import br.com.paywallet.user.UserRepository;

/**
 * TOTP second factor. After the password, users with it enabled get a short-lived challenge token instead of
 * session tokens and finish signing in with a code from their authenticator app, or with one of their single-use
 * recovery codes. A code is accepted once: the last time step used is remembered per user.
 */
@Service
public class MfaService {

    public record Setup(String secret, String otpauthUri) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RECOVERY_CODES = 8;
    private static final int CHALLENGE_ATTEMPTS = 5;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final SecurityProperties.Mfa props;
    private final Clock clock;

    public MfaService(UserRepository users, PasswordEncoder passwordEncoder, StringRedisTemplate redis,
                      JdbcTemplate jdbc, SecurityProperties props, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.jdbc = jdbc;
        this.props = props.mfa();
        this.clock = clock;
    }

    @Transactional
    public Setup setup(Long userId) {
        var user = users.findById(userId).orElseThrow();
        if (user.isTotpEnabled()) {
            throw new BusinessException("Two-factor authentication is already enabled; disable it first");
        }
        String secret = Totp.newSecret();
        user.startTotpEnrollment(secret);
        return new Setup(secret, Totp.otpauthUri(props.issuer(), user.getEmail(), secret));
    }

    /** Confirms the enrollment with a first code and returns the recovery codes, shown this once only. */
    @Transactional
    public List<String> enable(Long userId, String code) {
        var user = users.findById(userId).orElseThrow();
        if (user.isTotpEnabled() || user.getTotpSecret() == null) {
            throw new BusinessException("Start the setup before enabling two-factor authentication");
        }
        if (!acceptTotp(user, code)) {
            throw new BusinessException("Invalid code");
        }
        user.enableTotp(clock.instant());
        jdbc.update("DELETE FROM mfa_recovery_codes WHERE user_id = ?", userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            byte[] bytes = new byte[5];
            RANDOM.nextBytes(bytes);
            String recovery = Totp.base32(bytes);
            codes.add(recovery.substring(0, 4) + "-" + recovery.substring(4));
            jdbc.update("INSERT INTO mfa_recovery_codes (id, user_id, code_hash, created_at) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), userId, sha256(recovery), Timestamp.from(clock.instant()));
        }
        return codes;
    }

    @Transactional
    public void disable(Long userId, String password, String code) {
        var user = users.findById(userId).orElseThrow();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("Password is incorrect");
        }
        if (!user.isTotpEnabled() || !(acceptTotp(user, code) || acceptRecoveryCode(user, code))) {
            throw new BusinessException("Invalid code");
        }
        user.disableTotp();
        jdbc.update("DELETE FROM mfa_recovery_codes WHERE user_id = ?", userId);
    }

    String startChallenge(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(challengeKey(token), userId.toString(), props.challengeTtl());
        return token;
    }

    Duration challengeTtl() {
        return props.challengeTtl();
    }

    /** @return the user who passed the challenge; a challenge allows a few wrong codes and is then discarded */
    @Transactional
    public User completeChallenge(String token, String code) {
        String key = challengeKey(token);
        String userId = redis.opsForValue().get(key);
        if (userId == null) {
            throw new InvalidCredentialsException();
        }
        var user = users.findById(Long.valueOf(userId)).orElseThrow(InvalidCredentialsException::new);
        if (acceptTotp(user, code) || acceptRecoveryCode(user, code)) {
            redis.delete(key);
            redis.delete(key + ":attempts");
            return user;
        }
        Long attempts = redis.opsForValue().increment(key + ":attempts");
        redis.expire(key + ":attempts", props.challengeTtl());
        if (attempts != null && attempts >= CHALLENGE_ATTEMPTS) {
            redis.delete(key);
        }
        throw new InvalidCredentialsException();
    }

    private boolean acceptTotp(User user, String code) {
        var step = Totp.verify(user.getTotpSecret(), code, clock.instant());
        if (step.isEmpty()) {
            return false;
        }
        String lastKey = "mfa:last-step:" + user.getId();
        String last = redis.opsForValue().get(lastKey);
        if (last != null && Long.parseLong(last) >= step.getAsLong()) {
            return false;
        }
        redis.opsForValue().set(lastKey, Long.toString(step.getAsLong()), Duration.ofMinutes(5));
        return true;
    }

    private boolean acceptRecoveryCode(User user, String code) {
        if (code == null) {
            return false;
        }
        String normalized = code.replace("-", "").trim().toUpperCase(Locale.ROOT);
        return jdbc.update("""
                UPDATE mfa_recovery_codes SET used_at = ? WHERE user_id = ? AND code_hash = ? AND used_at IS NULL
                """, Timestamp.from(clock.instant()), user.getId(), sha256(normalized)) == 1;
    }

    private static String challengeKey(String token) {
        return "mfa:challenge:" + sha256(token);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
