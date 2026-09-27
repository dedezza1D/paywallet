package br.com.paywallet.auth;

import java.time.Clock;
import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.InvalidTransactionPinException;
import br.com.paywallet.exception.TooManyRequestsException;
import br.com.paywallet.exception.TransactionPinRequiredException;
import br.com.paywallet.user.UserRepository;

/**
 * A 6-digit PIN, separate from the password, confirms every outflow: a stolen session or password alone cannot move
 * money. Stored with BCrypt; after a few wrong PINs outflows are locked for a while, since 6 digits fall quickly to
 * guessing.
 */
@Service
public class TransactionPinService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final SecurityProperties.Pin props;
    private final Clock clock;

    public TransactionPinService(UserRepository users, PasswordEncoder passwordEncoder, StringRedisTemplate redis,
                                 SecurityProperties props, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
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
        user.changeTransactionPin(passwordEncoder.encode(pin), clock.instant());
        redis.delete(failuresKey(userId));
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
        String failures = redis.opsForValue().get(key);
        if (failures != null && Long.parseLong(failures) >= props.maxAttempts()) {
            Long ttl = redis.getExpire(key);
            throw new TooManyRequestsException("Too many wrong PINs. Outflows are locked for a while.",
                    ttl == null || ttl < 0 ? props.lockDuration() : Duration.ofSeconds(ttl));
        }
        if (!passwordEncoder.matches(pin, user.getTransactionPinHash())) {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, props.lockDuration());
            }
            throw new InvalidTransactionPinException();
        }
        redis.delete(key);
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
