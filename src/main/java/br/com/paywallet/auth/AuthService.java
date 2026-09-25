package br.com.paywallet.auth;

import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import br.com.paywallet.auth.AuthDtos.TokenResponse;
import br.com.paywallet.auth.RefreshTokenService.IssuedRefreshToken;
import br.com.paywallet.exception.InvalidCredentialsException;
import br.com.paywallet.exception.InvalidRefreshTokenException;
import br.com.paywallet.user.User;
import br.com.paywallet.user.UserRepository;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final RefreshTokenService refreshTokens;
    private final LoginAttemptLimiter limiter;
    private final SecurityProperties props;
    /**
     * Hash checked when the email does not exist, so BCrypt still runs and response time does not
     * reveal whether an account exists.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                       RefreshTokenService refreshTokens, LoginAttemptLimiter limiter, SecurityProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.limiter = limiter;
        this.props = props;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public TokenResponse login(String email, String password) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        limiter.checkAllowed(normalized);

        var user = users.findByEmail(normalized);
        boolean matches = passwordEncoder.matches(password, user.map(User::getPassword).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            limiter.recordFailure(normalized);
            throw new InvalidCredentialsException();
        }
        limiter.reset(normalized);
        return respond(user.get(), refreshTokens.startFamily(user.get().getId()));
    }

    public TokenResponse refresh(String refreshToken) {
        var rotation = refreshTokens.rotate(refreshToken);
        var user = users.findById(rotation.userId()).orElseThrow(InvalidRefreshTokenException::new);
        return respond(user, rotation.next());
    }

    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private TokenResponse respond(User user, IssuedRefreshToken refresh) {
        var access = tokens.issue(user);
        return new TokenResponse(access.value(), "Bearer", props.jwt().accessTokenTtl().toSeconds(),
                refresh.value(), props.refreshTokenTtl().toSeconds());
    }
}
