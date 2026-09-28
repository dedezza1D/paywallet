package br.com.paywallet.auth;

import java.util.Locale;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import br.com.paywallet.auth.AuthDtos.LoginResult;
import br.com.paywallet.auth.AuthDtos.MfaChallengeResponse;
import br.com.paywallet.auth.AuthDtos.TokenResponse;
import br.com.paywallet.auth.RefreshTokenService.IssuedRefreshToken;
import br.com.paywallet.exception.EmailNotVerifiedException;
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
    private final MfaService mfa;
    private final DeviceAlerts devices;
    private final SecurityProperties props;
    /**
     * Hash checked when the email does not exist, so BCrypt still runs and response time does not
     * reveal whether an account exists.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                       RefreshTokenService refreshTokens, LoginAttemptLimiter limiter, MfaService mfa,
                       DeviceAlerts devices, SecurityProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.limiter = limiter;
        this.mfa = mfa;
        this.devices = devices;
        this.props = props;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    public LoginResult login(String email, String password, DeviceAlerts.Device device) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        var slot = limiter.acquire(normalized);
        Optional<User> user = Optional.empty();
        boolean matches = false;
        try {
            user = users.findByEmail(normalized);
            matches = passwordEncoder.matches(password, user.map(User::getPassword).orElse(dummyHash));
        } finally {
            if (user.isPresent() && matches) {
                slot.succeeded();
            } else {
                slot.failed();
            }
        }
        if (user.isEmpty() || !matches) {
            throw new InvalidCredentialsException();
        }
        if (!user.get().isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        if (user.get().isTotpEnabled()) {
            return new LoginResult(null, new MfaChallengeResponse(true, mfa.startChallenge(user.get().getId()),
                    mfa.challengeTtl().toSeconds()));
        }
        return new LoginResult(signIn(user.get(), device), null);
    }

    public TokenResponse completeMfa(String mfaToken, String code, DeviceAlerts.Device device) {
        return signIn(mfa.completeChallenge(mfaToken, code), device);
    }

    private TokenResponse signIn(User user, DeviceAlerts.Device device) {
        devices.recordSignIn(user, device);
        return respond(user, refreshTokens.startFamily(user.getId()));
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
