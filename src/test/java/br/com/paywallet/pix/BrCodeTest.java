package br.com.paywallet.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import br.com.paywallet.user.Documents;
import br.com.paywallet.exception.BusinessException;

class BrCodeTest {

    /** Static QR code example from the central bank's Pix initiation standards manual. */
    private static final String BCB_EXAMPLE = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000"
            + "5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***63041D3D";

    @Test
    void parsesTheCentralBankExample() {
        var code = BrCode.parse(BCB_EXAMPLE);

        assertThat(code.key()).isEqualTo("123e4567-e12b-12d1-a456-426655440000");
        assertThat(code.amount()).isNull();
        assertThat(code.merchantName()).isEqualTo("Fulano de Tal");
        assertThat(code.merchantCity()).isEqualTo("BRASILIA");
        assertThat(code.txid()).isEqualTo("***");
    }

    @Test
    void encodesAndParsesBack() {
        var original = new BrCode("mary@mail.com", new BigDecimal("25.50"), "Crème brûlée",
                "Zoë Brontë", "Zürich", "ORDER123");

        String payload = original.encode();
        var parsed = BrCode.parse(payload);

        assertThat(payload).startsWith("000201").contains("br.gov.bcb.pix").contains("540525.50");
        assertThat(parsed.key()).isEqualTo("mary@mail.com");
        assertThat(parsed.amount()).isEqualByComparingTo("25.50");
        assertThat(parsed.description()).isEqualTo("Creme brulee");
        assertThat(parsed.merchantName()).isEqualTo("ZOE BRONTE");
        assertThat(parsed.merchantCity()).isEqualTo("ZURICH");
        assertThat(parsed.txid()).isEqualTo("ORDER123");
    }

    @Test
    void rejectsTamperedPayload() {
        String tampered = BCB_EXAMPLE.replace("Fulano", "Fulana");
        assertThatThrownBy(() -> BrCode.parse(tampered)).isInstanceOf(BusinessException.class);
    }

    @Test
    void endToEndIdFollowsTheCentralBankFormat() {
        String id = EndToEndIds.generate("12345678", java.time.Instant.parse("2026-09-25T18:30:00Z"));
        assertThat(id).hasSize(32).startsWith("E12345678202609251830").matches("E\\d{20}[A-Za-z0-9]{11}");
    }

    @Test
    void masksIndividualDocuments() {
        assertThat(Documents.mask("12345678901")).isEqualTo("***.456.789-**");
        assertThat(Documents.mask("12345678000199")).isEqualTo("12.345.678/0001-99");
    }
}
