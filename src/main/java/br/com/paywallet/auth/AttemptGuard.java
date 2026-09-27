package br.com.paywallet.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import br.com.paywallet.exception.TooManyRequestsException;

/**
 * Caps wrong guesses of a secret (password, PIN, one-time code) even when they arrive at the same time. Checking a
 * guess is slow (BCrypt), so a counter read before the check and incremented after it lets a burst of parallel
 * guesses through. Here every guess first reserves a slot, and failed plus in-flight guesses never exceed the
 * maximum; extra concurrent requests wait briefly for a slot instead of being rejected.
 */
@Component
class AttemptGuard {

    private static final Duration WAIT = Duration.ofSeconds(3);
    private static final long LOCKED = -1;
    private static final long BUSY = 0;

    /** KEYS: failures, pending. ARGV: max. Returns -1 when locked, 0 when every slot is taken, 1 when reserved. */
    private static final RedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local max = tonumber(ARGV[1])
            local failures = tonumber(redis.call('GET', KEYS[1]) or '0')
            if failures >= max then return -1 end
            if failures + tonumber(redis.call('GET', KEYS[2]) or '0') >= max then return 0 end
            redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], 30)
            return 1
            """, Long.class);

    /** KEYS: failures, pending. ARGV: 1 for a wrong guess, 0 for a right one; lock duration in ms. */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if tonumber(redis.call('GET', KEYS[2]) or '0') > 0 then redis.call('DECR', KEYS[2]) end
            if ARGV[1] == '0' then
              redis.call('DEL', KEYS[1])
              return 0
            end
            local failures = redis.call('INCR', KEYS[1])
            if failures == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return failures
            """, Long.class);

    private final StringRedisTemplate redis;

    AttemptGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Reserves a guess for {@code key}, waiting while other guesses are in flight.
     *
     * @return the slot, or null when the key is locked by too many wrong guesses
     */
    Slot acquire(String key, int max, Duration lockDuration) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        List<String> keys = List.of(key, key + ":pending");
        while (true) {
            Long result = redis.execute(RESERVE, keys, Integer.toString(max));
            if (result != null && result == LOCKED) {
                return null;
            }
            if (result == null || result != BUSY) {
                return new Slot(keys, lockDuration);
            }
            if (System.nanoTime() > deadline) {
                throw busy();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw busy();
            }
        }
    }

    private static TooManyRequestsException busy() {
        return new TooManyRequestsException("Too many attempts in progress. Please try again.", Duration.ofSeconds(1));
    }

    /** How long {@code key} stays locked, for the Retry-After header. */
    Duration lockedFor(String key, Duration fallback) {
        Long ttl = redis.getExpire(key);
        return ttl == null || ttl < 0 ? fallback : Duration.ofSeconds(ttl);
    }

    void reset(String key) {
        redis.delete(List.of(key, key + ":pending"));
    }

    final class Slot {

        private final List<String> keys;
        private final Duration lockDuration;

        private Slot(List<String> keys, Duration lockDuration) {
            this.keys = keys;
            this.lockDuration = lockDuration;
        }

        void succeeded() {
            redis.execute(RELEASE, keys, "0", "0");
        }

        /** @return wrong guesses so far, this one included */
        long failed() {
            Long failures = redis.execute(RELEASE, keys, "1", Long.toString(lockDuration.toMillis()));
            return failures == null ? 0 : failures;
        }
    }
}
