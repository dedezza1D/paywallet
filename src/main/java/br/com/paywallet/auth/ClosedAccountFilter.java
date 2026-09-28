package br.com.paywallet.auth;

import java.io.IOException;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects access tokens issued before their account was closed, which stay valid until they expire. Fails open if
 * Redis is down: a closed account has no balance, and moving money already fails closed without Redis.
 */
class ClosedAccountFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;

    ClosedAccountFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
                && isClosed(jwt.getToken().getSubject())) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Unauthorized","status":401,"detail":"This account is closed"}""");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isClosed(String userId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(AccountClosureService.closedKey(Long.valueOf(userId))));
        } catch (DataAccessException e) {
            return false;
        }
    }
}
