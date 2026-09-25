package br.com.paywallet.pix;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Expects {@code hex(HMAC-SHA256(secret, timestamp + "." + body))}. Signing the timestamp bounds how long a
 * captured request can be replayed; the comparison is constant-time.
 */
@Component
class WebhookSignatureVerifier {

    private final PixProperties props;
    private final Clock clock;

    WebhookSignatureVerifier(PixProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    boolean isValid(String timestamp, String signature, String body) {
        if (!StringUtils.hasText(props.webhookSecret()) || timestamp == null || signature == null) {
            return false;
        }
        Instant sentAt;
        try {
            sentAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(sentAt, clock.instant()).abs().compareTo(props.webhookTolerance()) > 0) {
            return false;
        }
        byte[] expected = sign(props.webhookSecret(), timestamp + "." + body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    static String sign(String secret, String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
