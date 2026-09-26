package br.com.paywallet.fraud;

import java.util.Locale;

/**
 * An outflow about to happen. {@code counterparty} identifies who receives the money in a channel-independent way
 * ({@code user:42}, {@code pix:mary@mail.com}, {@code doc:12345678000199}, {@code merchant:BOOK STORE}); null
 * when there is none worth tracking.
 */
public record FraudCheck(Long userId, Channel channel, long amountCents, String counterparty) {

    public static String user(Long userId) {
        return "user:" + userId;
    }

    public static String pixKey(String key) {
        return "pix:" + key;
    }

    public static String document(String document) {
        return document == null ? null : "doc:" + document;
    }

    public static String merchant(String name) {
        return "merchant:" + name.trim().toUpperCase(Locale.ROOT);
    }
}
