package br.com.paywallet.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class VoucherCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static VoucherCipher cipher(String key) {
        return new VoucherCipher(new MarketplaceProperties(Duration.ofSeconds(1), key, ZoneId.of("UTC")));
    }

    @Test
    void roundTripsWithAFreshIvEachTime() {
        var cipher = cipher(KEY);
        String first = cipher.encrypt("ABCD-EFGH-JKLM-NPQR");
        String second = cipher.encrypt("ABCD-EFGH-JKLM-NPQR");

        assertThat(first).isNotEqualTo(second).doesNotContain("ABCD");
        assertThat(cipher.decrypt(first)).isEqualTo("ABCD-EFGH-JKLM-NPQR");
        assertThat(cipher(KEY).decrypt(second)).isEqualTo("ABCD-EFGH-JKLM-NPQR");
    }

    @Test
    void rejectsTamperedValuesAndOtherKeys() {
        var cipher = cipher(KEY);
        byte[] data = Base64.getDecoder().decode(cipher.encrypt("ABCD-EFGH-JKLM-NPQR"));
        data[data.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(data)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(null).decrypt(cipher.encrypt("x"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 bytes");
    }
}
