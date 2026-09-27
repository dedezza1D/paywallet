package br.com.paywallet.user;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import br.com.paywallet.exception.BusinessException;

/**
 * Length matters more than composition rules: at least 12 characters, and at most 72 bytes, the part BCrypt reads.
 * A password must not contain the account's email name or document, the first things an attacker tries.
 */
public final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void check(String password, String email, String document) {
        if (password.length() < MIN_LENGTH) {
            throw new BusinessException("Password must have at least %d characters".formatted(MIN_LENGTH));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessException("Password must have at most %d bytes".formatted(MAX_BYTES));
        }
        String lower = password.toLowerCase(Locale.ROOT);
        String emailName = email.substring(0, Math.max(0, email.indexOf('@'))).toLowerCase(Locale.ROOT);
        if ((emailName.length() >= 4 && lower.contains(emailName)) || lower.contains(document)) {
            throw new BusinessException("Password must not contain your email or document");
        }
    }
}
