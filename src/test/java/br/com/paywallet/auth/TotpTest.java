package br.com.paywallet.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Vectors from RFC 6238, appendix B (SHA-1), truncated to 6 digits. */
class TotpTest {

    private static final String SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfcTestVectors() {
        assertThat(SECRET).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.code(SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.code(SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void acceptsOneStepOfDriftAndReportsTheStep() {
        Instant now = Instant.ofEpochSecond(1_234_567_890L);
        long step = now.getEpochSecond() / Totp.STEP_SECONDS;

        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, step + 2), now)).isEmpty();
        assertThat(Totp.verify(SECRET, "12345", now)).isEmpty();
    }

    @Test
    void base32RoundTrips() {
        byte[] bytes = {0, 1, 2, (byte) 250, (byte) 255, 42, 7};
        assertThat(Totp.base32Decode(Totp.base32(bytes))).containsExactly(bytes);
    }
}
