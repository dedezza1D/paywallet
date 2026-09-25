package br.com.paywallet.hotdata;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Short-lived lock against double clicks and concurrent retries with the same Idempotency-Key.
 * This is only the fast path; the real guarantee is the UNIQUE constraint on ledger_transactions.idempotency_key.
 */
@Component
public class IdempotencyGuard {

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    /** Deletes the lock only if it is still owned by the caller, so one request never releases another's lock. */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public IdempotencyGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return the lock token, or empty if another request with the same key is in progress */
    public Optional<String> tryAcquire(String key) {
        var token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(redisKey(key), token, LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    public void release(String key, String token) {
        redis.execute(RELEASE, List.of(redisKey(key)), token);
    }

    private static String redisKey(String key) {
        return "idem:" + key;
    }
}
