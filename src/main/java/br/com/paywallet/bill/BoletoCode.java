package br.com.paywallet.bill;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import br.com.paywallet.exception.BusinessException;

/**
 * A boleto as typed or scanned by the payer, in any of its forms: 44-digit barcode, 47-digit digitable line of
 * bank boletos, or 48-digit line of utility and tax bills (which always start with 8). Every check digit is
 * validated, so a typo is caught before any lookup.
 *
 * @param bankCode    FEBRABAN bank code; null for utility bills
 * @param dueDate     null when the bill carries none (utility bills and bank boletos with factor 0)
 * @param amountCents null when the amount is not in the code (open-amount boletos, reference-value utility bills)
 */
public record BoletoCode(Kind kind, String barcode, String digitableLine, String bankCode, LocalDate dueDate,
                         Long amountCents) {

    public enum Kind { BANK, UTILITY }

    /** The due date factor counts days from this base; it reached 9999 on 2025-02-21 and restarted at 1000. */
    private static final LocalDate FACTOR_BASE = LocalDate.of(1997, 10, 7);
    private static final LocalDate FACTOR_BASE_2025 = LocalDate.of(2025, 2, 22);

    public static BoletoCode parse(String input, LocalDate today) {
        String digits = input == null ? "" : input.replaceAll("[\\s.\\-]", "");
        if (!digits.matches("\\d+")) {
            throw invalid();
        }
        return switch (digits.length()) {
            case 44 -> digits.charAt(0) == '8' ? utility(digits, today) : bank(digits, today);
            case 47 -> bank(bankLineToBarcode(digits), today);
            case 48 -> utility(utilityLineToBarcode(digits), today);
            default -> throw invalid();
        };
    }

    // Bank boletos: barcode = bank(3) currency(1) DV(1) factor(4) amount(10) free field(25)

    private static BoletoCode bank(String barcode, LocalDate today) {
        if (barcode.charAt(4) - '0' != bankCheckDigit(barcode.substring(0, 4) + barcode.substring(5))) {
            throw invalid();
        }
        int factor = Integer.parseInt(barcode.substring(5, 9));
        long amount = Long.parseLong(barcode.substring(9, 19));
        return new BoletoCode(Kind.BANK, barcode, bankBarcodeToLine(barcode), barcode.substring(0, 3),
                dueDate(factor, today), amount == 0 ? null : amount);
    }

    private static String bankLineToBarcode(String line) {
        String field1 = line.substring(0, 10);
        String field2 = line.substring(10, 21);
        String field3 = line.substring(21, 32);
        for (String field : new String[] {field1, field2, field3}) {
            String data = field.substring(0, field.length() - 1);
            if (field.charAt(field.length() - 1) - '0' != mod10(data)) {
                throw invalid();
            }
        }
        return line.substring(0, 4) + line.charAt(32) + line.substring(33, 47)
                + line.substring(4, 9) + line.substring(10, 20) + line.substring(21, 31);
    }

    static String bankBarcodeToLine(String barcode) {
        String f1 = barcode.substring(0, 4) + barcode.substring(19, 24);
        String f2 = barcode.substring(24, 34);
        String f3 = barcode.substring(34, 44);
        return f1 + mod10(f1) + f2 + mod10(f2) + f3 + mod10(f3) + barcode.charAt(4) + barcode.substring(5, 19);
    }

    /** Modulo 11 with weights 2-9 from the right; results 0, 10 and 11 become 1. */
    static int bankCheckDigit(String digits43) {
        int dv = 11 - weightedSum(digits43) % 11;
        return dv == 0 || dv == 10 || dv == 11 ? 1 : dv;
    }

    /** Of the two possible dates for a factor (before and after the 2025 restart), the one closest to today. */
    private static LocalDate dueDate(int factor, LocalDate today) {
        if (factor == 0) {
            return null;
        }
        LocalDate old = FACTOR_BASE.plusDays(factor);
        if (factor < 1000) {
            return old;
        }
        LocalDate current = FACTOR_BASE_2025.plusDays(factor - 1000L);
        return Math.abs(ChronoUnit.DAYS.between(today, old)) < Math.abs(ChronoUnit.DAYS.between(today, current))
                ? old : current;
    }

    // Utility bills: barcode = 8 segment(1) value-indicator(1) DV(1) amount(11) company/free(29)

    private static BoletoCode utility(String barcode, LocalDate today) {
        boolean mod10 = usesMod10(barcode);
        String withoutDv = barcode.substring(0, 3) + barcode.substring(4);
        if (barcode.charAt(3) - '0' != (mod10 ? mod10(withoutDv) : utilityMod11(withoutDv))) {
            throw invalid();
        }
        long amount = Long.parseLong(barcode.substring(4, 15));
        boolean realValue = barcode.charAt(2) == '6' || barcode.charAt(2) == '8';
        return new BoletoCode(Kind.UTILITY, barcode, utilityBarcodeToLine(barcode), null, null,
                realValue && amount > 0 ? amount : null);
    }

    private static String utilityLineToBarcode(String line) {
        var barcode = new StringBuilder(44);
        boolean mod10 = usesMod10(line);
        for (int block = 0; block < 4; block++) {
            String data = line.substring(block * 12, block * 12 + 11);
            int dv = line.charAt(block * 12 + 11) - '0';
            if (dv != (mod10 ? mod10(data) : utilityMod11(data))) {
                throw invalid();
            }
            barcode.append(data);
        }
        return barcode.toString();
    }

    static String utilityBarcodeToLine(String barcode) {
        boolean mod10 = usesMod10(barcode);
        var line = new StringBuilder(48);
        for (int block = 0; block < 4; block++) {
            String data = barcode.substring(block * 11, block * 11 + 11);
            line.append(data).append(mod10 ? mod10(data) : utilityMod11(data));
        }
        return line.toString();
    }

    /** The third digit selects the check digit algorithm: 6 and 7 use modulo 10, 8 and 9 modulo 11. */
    private static boolean usesMod10(String code) {
        char indicator = code.charAt(2);
        if (indicator < '6' || indicator > '9') {
            throw invalid();
        }
        return indicator == '6' || indicator == '7';
    }

    static int utilityMod11(String digits) {
        int rest = weightedSum(digits) % 11;
        return rest == 0 || rest == 1 ? 0 : rest == 10 ? 1 : 11 - rest;
    }

    /** Weights 2 and 1 alternating from the right; two-digit products contribute the sum of their digits. */
    static int mod10(String digits) {
        int sum = 0;
        int weight = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int product = (digits.charAt(i) - '0') * weight;
            sum += product / 10 + product % 10;
            weight = weight == 2 ? 1 : 2;
        }
        return (10 - sum % 10) % 10;
    }

    private static int weightedSum(String digits) {
        int sum = 0;
        int weight = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            sum += (digits.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        return sum;
    }

    private static BusinessException invalid() {
        return new BusinessException("Invalid boleto code");
    }
}
