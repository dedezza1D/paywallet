package br.com.paywallet.pix;

import java.util.Locale;
import java.util.regex.Pattern;

import br.com.paywallet.exception.BusinessException;

public enum PixKeyType {
    CPF(Pattern.compile("\\d{11}")),
    CNPJ(Pattern.compile("\\d{14}")),
    EMAIL(Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")),
    PHONE(Pattern.compile("\\+55\\d{10,11}")),
    EVP(Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));

    private static final int MAX_LENGTH = 77;

    private final Pattern format;

    PixKeyType(Pattern format) {
        this.format = format;
    }

    public String normalize(String raw) {
        if (raw == null) {
            throw new BusinessException("Pix key is required");
        }
        String value = switch (this) {
            case CPF, CNPJ -> raw.replaceAll("[.\\-/\\s]", "");
            case PHONE -> raw.replaceAll("[\\s()\\-]", "");
            case EMAIL, EVP -> raw.trim().toLowerCase(Locale.ROOT);
        };
        if (value.length() > MAX_LENGTH || !format.matcher(value).matches()) {
            throw new BusinessException("Invalid %s Pix key".formatted(name()));
        }
        return value;
    }

    /** Infers the type of a key typed by a payer. Phones must carry the +55 prefix, as in the DICT. */
    public static PixKeyType detect(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("+")) {
            return PHONE;
        }
        if (value.contains("@")) {
            return EMAIL;
        }
        if (EVP.format.matcher(value.toLowerCase(Locale.ROOT)).matches()) {
            return EVP;
        }
        String digits = value.replaceAll("[.\\-/\\s]", "");
        if (CPF.format.matcher(digits).matches()) {
            return CPF;
        }
        if (CNPJ.format.matcher(digits).matches()) {
            return CNPJ;
        }
        throw new BusinessException("Unrecognized Pix key format");
    }
}
