package br.com.paywallet.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import br.com.paywallet.auth.AccountCodes.Purpose;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.external.EmailSender;
import br.com.paywallet.user.PasswordPolicy;
import br.com.paywallet.user.User;
import br.com.paywallet.user.UserRegisteredEvent;
import br.com.paywallet.user.UserRepository;

/**
 * Email verification and password recovery with 6-digit codes. Requests that name an email answer the same way
 * whether or not the account exists, so they cannot be used to find out who is a customer. Codes are sent at most
 * once per cooldown and a few times per hour per email.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final String INVALID_CODE = "Invalid or expired code";

    private final UserRepository users;
    private final AccountCodes codes;
    private final EmailSender email;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokens;
    private final StringRedisTemplate redis;
    private final SecurityProperties.Codes props;
    private final Clock clock;

    public AccountService(UserRepository users, AccountCodes codes, EmailSender email, PasswordEncoder passwordEncoder,
                          RefreshTokenRepository refreshTokens, StringRedisTemplate redis, SecurityProperties props,
                          Clock clock) {
        this.users = users;
        this.codes = codes;
        this.email = email;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.redis = redis;
        this.props = props.codes();
        this.clock = clock;
    }

    /** After the sign-up commits, so no email goes out for an account that was rolled back. */
    @TransactionalEventListener
    public void onRegistered(UserRegisteredEvent event) {
        users.findById(event.userId()).ifPresent(this::sendVerificationCode);
    }

    public void resendVerificationCode(String rawEmail) {
        users.findByEmail(normalize(rawEmail)).filter(u -> !u.isEmailVerified()).ifPresent(this::sendVerificationCode);
    }

    @Transactional
    public void verifyEmail(String rawEmail, String code) {
        var user = users.findByEmail(normalize(rawEmail)).orElseThrow(() -> new BusinessException(INVALID_CODE));
        if (user.isEmailVerified()) {
            return;
        }
        if (!codes.consume(user.getId(), Purpose.EMAIL_VERIFICATION, code)) {
            throw new BusinessException(INVALID_CODE);
        }
        user.verifyEmail(clock.instant());
    }

    public void forgotPassword(String rawEmail) {
        users.findByEmail(normalize(rawEmail)).ifPresent(user -> {
            if (allowSend(user.getEmail(), Purpose.PASSWORD_RESET)) {
                String code = codes.issue(user.getId(), Purpose.PASSWORD_RESET);
                deliver(user.getEmail(), "Reset your PayWallet password", """
                        Your password reset code is %s. It expires in %d minutes.

                        If you did not ask to reset your password, ignore this email: your password stays the same.
                        """.formatted(code, props.ttl().toMinutes()));
            }
        });
    }

    /** Also ends every session, since whoever knew the old password may be logged in. */
    @Transactional
    public void resetPassword(String rawEmail, String code, String newPassword) {
        var user = users.findByEmail(normalize(rawEmail)).orElseThrow(() -> new BusinessException(INVALID_CODE));
        PasswordPolicy.check(newPassword, user.getEmail(), user.getDocument());
        if (!codes.consume(user.getId(), Purpose.PASSWORD_RESET, code)) {
            throw new BusinessException(INVALID_CODE);
        }
        updatePassword(user, newPassword);
        user.verifyEmail(clock.instant());
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        var user = users.findById(userId).orElseThrow();
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BusinessException("Current password is incorrect");
        }
        PasswordPolicy.check(newPassword, user.getEmail(), user.getDocument());
        updatePassword(user, newPassword);
    }

    private void updatePassword(User user, String newPassword) {
        user.changePassword(passwordEncoder.encode(newPassword), clock.instant());
        refreshTokens.revokeAllForUser(user.getId(), clock.instant());
        deliver(user.getEmail(), "Your PayWallet password was changed", """
                The password of your PayWallet account was just changed and every session was signed out.

                If this was not you, contact support right away.
                """);
    }

    private void sendVerificationCode(User user) {
        if (!allowSend(user.getEmail(), Purpose.EMAIL_VERIFICATION)) {
            return;
        }
        String code = codes.issue(user.getId(), Purpose.EMAIL_VERIFICATION);
        deliver(user.getEmail(), "Confirm your PayWallet email", """
                Welcome to PayWallet! Your confirmation code is %s. It expires in %d minutes.
                """.formatted(code, props.ttl().toMinutes()));
    }

    /** A cooldown between codes of the same purpose plus an hourly cap per address. Fails open if Redis is down. */
    private boolean allowSend(String address, Purpose purpose) {
        try {
            String cooldown = "codes:cooldown:" + purpose + ":" + address;
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldown, "1", props.resendCooldown()))) {
                return false;
            }
            Long sent = redis.opsForValue().increment("codes:hourly:" + address);
            if (sent != null && sent == 1) {
                redis.expire("codes:hourly:" + address, Duration.ofHours(1));
            }
            return sent == null || sent <= props.maxSendsPerHour();
        } catch (DataAccessException e) {
            log.warn("Code rate limit skipped for {}: {}", address, e.getMessage());
            return true;
        }
    }

    private void deliver(String to, String subject, String text) {
        try {
            email.send(to, subject, text);
        } catch (MailException e) {
            log.error("Email '{}' to {} not sent: {}", subject, to, e.getMessage());
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
