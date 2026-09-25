package br.com.paywallet.auth;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import br.com.paywallet.exception.TooManyRequestsException;

/**
 * Brute-force brake: after N consecutive wrong passwords for the same email, further attempts get 429
 * until the lock expires. A successful login resets the counter.
 */
@Component
class LoginAttemptLimiter {

    private final StringRedisTemplate redis;
    private final SecurityProperties props;

    LoginAttemptLimiter(StringRedisTemplate redis, SecurityProperties props) {
        this.redis = redis;
        this.props = props;
    }

    void checkAllowed(String email) {
        String failures = redis.opsForValue().get(key(email));
        if (failures != null && Long.parseLong(failures) >= props.loginMaxAttempts()) {
            Long ttl = redis.getExpire(key(email));
            throw new TooManyRequestsException("Too many login attempts. Please try again later.",
                    ttl == null || ttl < 0 ? props.loginLockDuration() : Duration.ofSeconds(ttl));
        }
    }

    void recordFailure(String email) {
        Long count = redis.opsForValue().increment(key(email));
        if (count != null && count == 1) {
            redis.expire(key(email), props.loginLockDuration());
        }
    }

    void reset(String email) {
        redis.delete(key(email));
    }

    private static String key(String email) {
        return "login:fail:" + email;
    }
}
