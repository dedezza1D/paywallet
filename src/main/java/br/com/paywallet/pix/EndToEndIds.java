package br.com.paywallet.pix;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** BCB format: "E" + payer ISPB (8) + yyyyMMddHHmm in UTC + 11 alphanumeric characters = 32 characters. */
final class EndToEndIds {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private EndToEndIds() {
    }

    static String generate(String ispb, Instant now) {
        return generate('E', ispb, now);
    }

    /** Return ids follow the end-to-end format with a D prefix. */
    static String generateReturn(String ispb, Instant now) {
        return generate('D', ispb, now);
    }

    private static String generate(char prefix, String ispb, Instant now) {
        var id = new StringBuilder(32).append(prefix).append(ispb).append(TIMESTAMP.format(now));
        for (int i = 0; i < 11; i++) {
            id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }
}
