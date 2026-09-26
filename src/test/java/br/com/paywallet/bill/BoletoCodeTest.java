package br.com.paywallet.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import br.com.paywallet.exception.BusinessException;

class BoletoCodeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    /** Widely published Banco do Brasil sample: R$ 1.00, due date factor 3737 (2007-12-31). */
    private static final String BB_LINE = "00190500954014481606906809350314337370000000100";
    private static final String BB_BARCODE = "00193373700000001000500940144816060680935031";

    @Test
    void convertsThePublishedBankSampleBothWays() {
        var fromLine = BoletoCode.parse(BB_LINE, LocalDate.of(2008, 1, 1));
        var fromBarcode = BoletoCode.parse(BB_BARCODE, LocalDate.of(2008, 1, 1));

        assertThat(fromLine.barcode()).isEqualTo(BB_BARCODE);
        assertThat(fromBarcode.digitableLine()).isEqualTo(BB_LINE);
        assertThat(fromLine.kind()).isEqualTo(BoletoCode.Kind.BANK);
        assertThat(fromLine.bankCode()).isEqualTo("001");
        assertThat(fromLine.amountCents()).isEqualTo(100);
        assertThat(fromLine.dueDate()).isEqualTo(LocalDate.of(2007, 12, 31));
    }

    /** FEBRABAN reference point: factor 1000 is 2000-07-03 in the original cycle. */
    @Test
    void factorOneThousandIsTheFebrabanReferenceDate() {
        String barcode = bankBarcodeWithFactor("001", 1000, 100, "0000000000000000000000001");
        assertThat(BoletoCode.parse(barcode, LocalDate.of(2000, 7, 1)).dueDate()).isEqualTo(LocalDate.of(2000, 7, 3));
        assertThat(BoletoCode.parse(barcode, TODAY).dueDate()).isEqualTo(LocalDate.of(2025, 2, 22));
    }

    @Test
    void acceptsFormattedInput() {
        var formatted = "00190.50095 40144.816069 06809.350314 3 37370000000100";
        assertThat(BoletoCode.parse(formatted, TODAY).barcode()).isEqualTo(BB_BARCODE);
    }

    @Test
    void readsDueDatesAfterTheFactorRestartOf2025() {
        var due = LocalDate.of(2026, 10, 10);
        String barcode = bankBarcode("341", due, 15_990, "1090000055123456789012345");

        var code = BoletoCode.parse(BoletoCode.bankBarcodeToLine(barcode), TODAY);

        assertThat(code.dueDate()).isEqualTo(due);
        assertThat(code.amountCents()).isEqualTo(15_990);
        assertThat(code.bankCode()).isEqualTo("341");
    }

    @Test
    void openAmountBoletoHasNoAmount() {
        String barcode = bankBarcode("237", LocalDate.of(2026, 10, 1), 0, "0000000000000000000000001");
        assertThat(BoletoCode.parse(barcode, TODAY).amountCents()).isNull();
    }

    @Test
    void parsesUtilityBillsWithBothCheckDigitAlgorithms() {
        for (char indicator : new char[] {'6', '8'}) {
            String barcode = utilityBarcode('2', indicator, 12_345, "0001" + "20260930" + "00000000000000000");
            String line = BoletoCode.utilityBarcodeToLine(barcode);

            var code = BoletoCode.parse(line, TODAY);

            assertThat(line).hasSize(48);
            assertThat(code.kind()).isEqualTo(BoletoCode.Kind.UTILITY);
            assertThat(code.barcode()).isEqualTo(barcode);
            assertThat(code.amountCents()).isEqualTo(12_345);
            assertThat(code.dueDate()).isNull();
        }
    }

    @Test
    void rejectsAnyMistypedDigit() {
        for (int position : new int[] {0, 9, 15, 25, 32, 40, 46}) {
            char original = BB_LINE.charAt(position);
            String typo = BB_LINE.substring(0, position) + (char) ('0' + (original - '0' + 1) % 10)
                    + BB_LINE.substring(position + 1);
            assertThatThrownBy(() -> BoletoCode.parse(typo, TODAY))
                    .as("typo at position %d", position)
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void rejectsWrongLengthsAndNonDigits() {
        assertThatThrownBy(() -> BoletoCode.parse("123", TODAY)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> BoletoCode.parse(BB_LINE.replace('5', 'x'), TODAY)).isInstanceOf(BusinessException.class);
    }

    /** Builds a valid bank barcode, computing the general check digit. */
    static String bankBarcode(String bank, LocalDate due, long amountCents, String freeField) {
        long factor = due == null ? 0 : 1000 + ChronoUnit.DAYS.between(LocalDate.of(2025, 2, 22), due);
        return bankBarcodeWithFactor(bank, factor, amountCents, freeField);
    }

    static String bankBarcodeWithFactor(String bank, long factor, long amountCents, String freeField) {
        String withoutDv = bank + "9" + "%04d".formatted(factor) + "%010d".formatted(amountCents) + freeField;
        int dv = BoletoCode.bankCheckDigit(withoutDv);
        return withoutDv.substring(0, 4) + dv + withoutDv.substring(4);
    }

    /** Builds a valid utility barcode (segment, value indicator, amount, company data). */
    static String utilityBarcode(char segment, char indicator, long amountCents, String rest29) {
        String withoutDv = "8" + segment + indicator + "%011d".formatted(amountCents) + rest29;
        int dv = indicator == '6' || indicator == '7' ? BoletoCode.mod10(withoutDv) : BoletoCode.utilityMod11(withoutDv);
        return withoutDv.substring(0, 3) + dv + withoutDv.substring(3);
    }
}
