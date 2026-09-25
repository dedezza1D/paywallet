package br.com.paywallet.hotdata;

import java.time.Duration;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside balance for display only. Whether a transfer has enough funds is always decided by
 * Postgres under lock. Redis failures are tolerated here by falling back to the database.
 */
@Component
public class BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(BalanceCache.class);
    private static final Duration TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;

    public BalanceCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public OptionalLong get(Long userId) {
        try {
            String v = redis.opsForValue().get(key(userId));
            return v == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(v));
        } catch (DataAccessException e) {
            log.warn("Balance cache unavailable: {}", e.getMessage());
            return OptionalLong.empty();
        }
    }

    public void put(Long userId, long balanceCents) {
        try {
            redis.opsForValue().set(key(userId), String.valueOf(balanceCents), TTL);
        } catch (DataAccessException e) {
            log.warn("Failed to write balance cache: {}", e.getMessage());
        }
    }

    public void evict(Long userId) {
        try {
            redis.delete(key(userId));
        } catch (DataAccessException e) {
            log.warn("Failed to evict balance cache (expires in {}s): {}", TTL.toSeconds(), e.getMessage());
        }
    }

    private static String key(Long userId) {
        return "balance:" + userId;
    }
}
