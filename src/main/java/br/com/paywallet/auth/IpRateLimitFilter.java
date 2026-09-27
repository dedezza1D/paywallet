package br.com.paywallet.auth;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Caps requests per client IP and minute on the public authentication endpoints, the ones used for password
 * spraying, account enumeration and code guessing across many accounts. Complements the per-account limits;
 * fails open if Redis is down.
 */
@Component
class IpRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> PATHS = Set.of("/users", "/auth/login", "/auth/login/mfa", "/auth/email/verify",
            "/auth/email/verification-code", "/auth/password/forgot", "/auth/password/reset");

    private final StringRedisTemplate redis;
    private final int maxPerMinute;

    @Autowired
    IpRateLimitFilter(StringRedisTemplate redis, SecurityProperties props) {
        this(redis, props.ipRateLimit());
    }

    IpRateLimitFilter(StringRedisTemplate redis, int maxPerMinute) {
        this.redis = redis;
        this.maxPerMinute = maxPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = Instant.now().getEpochSecond();
        String key = "ratelimit:auth:" + request.getRemoteAddr() + ":" + now / 60;
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(60));
            }
        } catch (DataAccessException e) {
            count = null;
        }
        if (count != null && count > maxPerMinute) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(60 - now % 60));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Too Many Requests","status":429,\
                    "detail":"Too many requests from this address. Please try again in a minute."}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
