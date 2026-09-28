package br.com.paywallet.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.crypto.FieldCipher;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.InvalidTransactionPinException;
import br.com.paywallet.exception.TooManyRequestsException;
import br.com.paywallet.exception.TransactionPinRequiredException;
import br.com.paywallet.user.UserRepository;

/**
 * A 6-digit PIN, separate from the password, confirms every outflow: a stolen session or password alone cannot move
 * money. After a few wrong PINs outflows are locked for a while, since 6 digits fall quickly to guessing.
 * <p>
 * Stored as an HMAC under the KMS-protected index key rather than with BCrypt: a million possible PINs fall to an
 * offline BCrypt search in hours anyway, while the key keeps a leaked database useless, and BCrypt on every outflow
 * cost ~70 ms of CPU. Older BCrypt hashes are still accepted and replaced on the next right PIN.
 */
@Service
public class TransactionPinService {

    private static final String HMAC_PREFIX = "hmac:v1:";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AttemptGuard guard;
    private final SecurityProperties.Pin props;
    private final Clock clock;
    private final FieldCipher cipher;
    private final JdbcTemplate jdbc;

    public TransactionPinService(UserRepository users, PasswordEncoder passwordEncoder, AttemptGuard guard,
                                 SecurityProperties props, Clock clock, FieldCipher cipher, JdbcTemplate jdbc) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.guard = guard;
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.props = props.pin();
        this.clock = clock;
    }

    /** Setting or changing the PIN takes the account password, so a hijacked session cannot replace it. */
    @Transactional
    public void change(Long userId, String password, String pin) {
        var user = users.findById(userId).orElseThrow();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("Password is incorrect");
        }
        if (isTrivial(pin)) {
            throw new BusinessException("Choose a PIN that is not a repeated digit or a sequence");
        }
        user.changeTransactionPin(hash(userId, pin), clock.instant());
        guard.reset(failuresKey(userId));
    }

    public void verify(Long userId, String pin) {
        var user = users.findById(userId).orElseThrow();
        if (user.getTransactionPinHash() == null) {
            throw new TransactionPinRequiredException("Set a transaction PIN before moving money");
        }
        if (pin == null || pin.isBlank()) {
            throw new TransactionPinRequiredException("Transaction PIN required in the X-Transaction-Pin header");
        }
        String key = failuresKey(userId);
        var slot = guard.acquire(key, props.maxAttempts(), props.lockDuration());
        if (slot == null) {
            throw new TooManyRequestsException("Too many wrong PINs. Outflows are locked for a while.",
                    guard.lockedFor(key, props.lockDuration()));
        }
        boolean matches = false;
        try {
            matches = matches(userId, pin, user.getTransactionPinHash());
        } finally {
            if (matches) {
                slot.succeeded();
            } else {
                slot.failed();
            }
        }
        if (!matches) {
            throw new InvalidTransactionPinException();
        }
    }

    private boolean matches(Long userId, String pin, String stored) {
        if (stored.startsWith(HMAC_PREFIX)) {
            return MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                    hash(userId, pin).getBytes(StandardCharsets.US_ASCII));
        }
        if (!passwordEncoder.matches(pin, stored)) {
            return false;
        }
        jdbc.update("UPDATE users SET transaction_pin_hash = ? WHERE id = ? AND transaction_pin_hash = ?",
                hash(userId, pin), userId, stored);
        return true;
    }

    private String hash(Long userId, String pin) {
        return HMAC_PREFIX + cipher.blindIndex("transaction-pin:" + userId + ":" + pin);
    }

    static boolean isTrivial(String pin) {
        if (!pin.matches("\\d{6}")) {
            return true;
        }
        boolean repeated = pin.chars().distinct().count() == 1;
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < pin.length(); i++) {
            int step = pin.charAt(i) - pin.charAt(i - 1);
            ascending &= step == 1;
            descending &= step == -1;
        }
        return repeated || ascending || descending;
    }

    private static String failuresKey(Long userId) {
        return "pin:fail:" + userId;
    }
}
