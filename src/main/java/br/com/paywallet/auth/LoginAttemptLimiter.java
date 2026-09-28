package br.com.paywallet.auth;

import org.springframework.stereotype.Component;

import br.com.paywallet.exception.TooManyRequestsException;

/**
 * Brute-force brake: after N consecutive wrong passwords for the same email, further attempts get 429
 * until the lock expires. A successful login resets the counter.
 */
@Component
class LoginAttemptLimiter {

    private final AttemptGuard guard;
    private final SecurityProperties props;

    LoginAttemptLimiter(AttemptGuard guard, SecurityProperties props) {
        this.guard = guard;
        this.props = props;
    }

    AttemptGuard.Slot acquire(String email) {
        var slot = guard.acquire(key(email), props.loginMaxAttempts(), props.loginLockDuration());
        if (slot == null) {
            throw new TooManyRequestsException("Too many login attempts. Please try again later.",
                    guard.lockedFor(key(email), props.loginLockDuration()));
        }
        return slot;
    }

    private static String key(String email) {
        return "login:fail:" + email;
    }
}
