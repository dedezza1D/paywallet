package br.com.paywallet.external;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signatures shared by partner integrations: {@code hex(HMAC-SHA256(secret, timestamp + "." + body))}.
 * Signing the timestamp bounds how long a captured request can be replayed; comparison is constant-time.
 */
public final class HmacSignatures {

    private HmacSignatures() {
    }

    public static boolean isValid(String secret, Duration tolerance, Instant now, String timestamp, String signature,
                                  String body) {
        if (secret == null || secret.isBlank() || timestamp == null || signature == null) {
            return false;
        }
        Instant sentAt;
        try {
            sentAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(sentAt, now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = sign(secret, timestamp + "." + body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public static String sign(String secret, String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
