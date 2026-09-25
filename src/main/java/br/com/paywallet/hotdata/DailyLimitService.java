package br.com.paywallet.hotdata;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.ledger.Money;

/**
 * Daily outgoing limit tracked in Redis. Reserve before moving money and release if the movement fails.
 * The reservation is an atomic Lua script, so concurrent transfers can never exceed the limit together.
 */
@Component
public class DailyLimitService {

    /** KEYS[1]=counter, ARGV[1]=amount, ARGV[2]=limit, ARGV[3]=ttl seconds. Returns -1 when over the limit. */
    private static final RedisScript<Long> RESERVE = RedisScript.of("""
            local used = redis.call('INCRBY', KEYS[1], ARGV[1])
            if used == tonumber(ARGV[1]) then
              redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            if used > tonumber(ARGV[2]) then
              redis.call('DECRBY', KEYS[1], ARGV[1])
              return -1
            end
            return used
            """, Long.class);

    private static final Duration KEY_TTL = Duration.ofHours(26);

    private final StringRedisTemplate redis;
    private final LimitsProperties props;
    private final Clock clock;

    public DailyLimitService(StringRedisTemplate redis, LimitsProperties props, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    public record DailyLimit(long limitCents, long usedCents) {

        public long remainingCents() {
            return Math.max(0, limitCents - usedCents);
        }
    }

    public void reserve(Long userId, long amountCents) {
        Long result = redis.execute(RESERVE, List.of(key(userId)),
                String.valueOf(amountCents), String.valueOf(limitCents()), String.valueOf(KEY_TTL.toSeconds()));
        if (result == null || result < 0) {
            throw new BusinessException("Daily transfer limit exceeded");
        }
    }

    public void release(Long userId, long amountCents) {
        redis.opsForValue().decrement(key(userId), amountCents);
    }

    public DailyLimit current(Long userId) {
        String used = redis.opsForValue().get(key(userId));
        return new DailyLimit(limitCents(), used == null ? 0 : Long.parseLong(used));
    }

    private long limitCents() {
        return Money.toCents(props.dailyTransfer());
    }

    private String key(Long userId) {
        return "limit:transfer:%d:%s".formatted(userId, LocalDate.now(clock.withZone(props.zone())));
    }
}
