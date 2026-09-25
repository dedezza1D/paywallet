package br.com.paywallet.pix;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import br.com.paywallet.exception.BusinessException;

/**
 * Static Pix QR code ("BR Code"): an EMV merchant-presented payload of ID-length-value fields,
 * terminated by a CRC16/CCITT-FALSE checksum. This is the "Pix copy and paste" string.
 *
 * @param amount null lets the payer choose the amount
 */
public record BrCode(String key, BigDecimal amount, String description, String merchantName,
                     String merchantCity, String txid) {

    private static final String GUI = "br.gov.bcb.pix";
    private static final int MAX_TEMPLATE_LENGTH = 99;

    public String encode() {
        String accountInfo = field("00", GUI) + field("01", key);
        if (description != null && !description.isBlank()) {
            int room = MAX_TEMPLATE_LENGTH - accountInfo.length() - 4;
            if (room > 0) {
                accountInfo += field("02", truncate(ascii(description), room));
            }
        }
        var payload = new StringBuilder()
                .append(field("00", "01"))
                .append(field("26", accountInfo))
                .append(field("52", "0000"))
                .append(field("53", "986"));
        if (amount != null) {
            payload.append(field("54", amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        }
        payload.append(field("58", "BR"))
                .append(field("59", truncate(ascii(merchantName).toUpperCase(Locale.ROOT), 25)))
                .append(field("60", truncate(ascii(merchantCity).toUpperCase(Locale.ROOT), 15)))
                .append(field("62", field("05", txid == null || txid.isBlank() ? "***" : txid)))
                .append("6304");
        return payload + crc16(payload.toString());
    }

    public static BrCode parse(String payload) {
        if (payload == null || payload.length() < 8 || !payload.substring(payload.length() - 8, payload.length() - 4)
                .equals("6304")) {
            throw invalid();
        }
        String body = payload.substring(0, payload.length() - 4);
        if (!crc16(body).equalsIgnoreCase(payload.substring(payload.length() - 4))) {
            throw invalid();
        }
        Map<String, String> fields = tlv(body.substring(0, body.length() - 4));
        Map<String, String> account = tlv(fields.getOrDefault("26", ""));
        if (!GUI.equalsIgnoreCase(account.get("00")) || account.get("01") == null) {
            throw invalid();
        }
        Map<String, String> additional = tlv(fields.getOrDefault("62", ""));
        try {
            return new BrCode(account.get("01"),
                    fields.containsKey("54") ? new BigDecimal(fields.get("54")) : null,
                    account.get("02"), fields.get("59"), fields.get("60"), additional.get("05"));
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static Map<String, String> tlv(String data) {
        Map<String, String> fields = new LinkedHashMap<>();
        int i = 0;
        try {
            while (i < data.length()) {
                String id = data.substring(i, i + 2);
                int length = Integer.parseInt(data.substring(i + 2, i + 4));
                fields.put(id, data.substring(i + 4, i + 4 + length));
                i += 4 + length;
            }
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            throw invalid();
        }
        return fields;
    }

    private static String field(String id, String value) {
        if (value.length() > 99) {
            throw new IllegalArgumentException("Field " + id + " exceeds 99 characters");
        }
        return id + "%02d".formatted(value.length()) + value;
    }

    static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
            crc &= 0xFFFF;
        }
        return "%04X".formatted(crc);
    }

    /** BR Codes are ASCII-only: strips accents and anything outside printable ASCII. */
    private static String ascii(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("[^\\x20-\\x7E]", "").trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static BusinessException invalid() {
        return new BusinessException("Invalid Pix QR code");
    }
}
