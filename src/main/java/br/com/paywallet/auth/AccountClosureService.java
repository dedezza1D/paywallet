package br.com.paywallet.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.card.CardService;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.pix.PixKeyService;
import br.com.paywallet.user.UserRepository;

/**
 * Closes an account from the app, as the app stores require. Financial records must be kept for years, so nothing is
 * erased: the account stops signing in and receiving money, its Pix keys are released and its cards cancelled. Only an
 * account with nothing left to settle can be closed.
 */
@Service
public class AccountClosureService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfa;
    private final LedgerService ledger;
    private final PixKeyService pixKeys;
    private final CardService cards;
    private final RefreshTokenRepository refreshTokens;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final SecurityProperties props;
    private final Clock clock;

    public AccountClosureService(UserRepository users, PasswordEncoder passwordEncoder, MfaService mfa,
                                 LedgerService ledger, PixKeyService pixKeys, CardService cards,
                                 RefreshTokenRepository refreshTokens, JdbcTemplate jdbc, StringRedisTemplate redis,
                                 SecurityProperties props, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mfa = mfa;
        this.ledger = ledger;
        this.pixKeys = pixKeys;
        this.cards = cards;
        this.refreshTokens = refreshTokens;
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    /** What still prevents closing the account; empty when it can be closed. */
    @Transactional(readOnly = true)
    public List<String> blockers(Long userId) {
        List<String> blockers = new ArrayList<>();
        if (ledger.walletOf(userId).getBalance() != 0) {
            blockers.add("Transfer out your balance");
        }
        if (count("SELECT count(*) FROM loans WHERE user_id = ? AND status = 'ACTIVE'", userId) > 0) {
            blockers.add("Pay off your loans");
        }
        if (cards.list(userId).stream().anyMatch(c -> c.creditLimit() != null
                && c.availableLimit().compareTo(c.creditLimit()) < 0)) {
            blockers.add("Pay your credit card balance");
        }
        if (count("""
                SELECT (SELECT count(*) FROM pix_payments WHERE payer_user_id = ? AND status = 'PENDING')
                     + (SELECT count(*) FROM bill_payments WHERE payer_user_id = ? AND status = 'PENDING')
                     + (SELECT count(*) FROM marketplace_orders WHERE user_id = ? AND status = 'PENDING')
                     + (SELECT count(*) FROM card_authorizations a JOIN cards c ON c.id = a.card_id
                         WHERE c.user_id = ? AND a.status = 'APPROVED')
                """, userId, userId, userId, userId) > 0) {
            blockers.add("Wait for your pending payments and card purchases to settle");
        }
        if (count("SELECT count(*) FROM charges WHERE merchant_id = ? AND status = 'PENDING'", userId) > 0) {
            blockers.add("Cancel your open charges");
        }
        return blockers;
    }

    @Transactional
    public void close(Long userId, String password, String code) {
        var user = users.findById(userId).orElseThrow();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("Password is incorrect");
        }
        if (user.isTotpEnabled() && !mfa.verifySecondFactor(user, code)) {
            throw new BusinessException("Invalid two-factor code");
        }
        var blockers = blockers(userId);
        if (!blockers.isEmpty()) {
            throw new BusinessException("The account cannot be closed yet: " + String.join("; ", blockers));
        }
        pixKeys.list(userId).forEach(key -> pixKeys.delete(userId, key.getId()));
        cards.list(userId).forEach(card -> cards.cancel(userId, card.id()));
        refreshTokens.revokeAllForUser(userId, clock.instant());
        user.close(clock.instant());
        // Access tokens are stateless; this marker rejects the ones still valid until they expire.
        redis.opsForValue().set(closedKey(userId), "1", props.jwt().accessTokenTtl().plus(Duration.ofMinutes(1)));
    }

    static String closedKey(Long userId) {
        return "account:closed:" + userId;
    }

    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }
}
