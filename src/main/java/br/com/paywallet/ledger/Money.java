package br.com.paywallet.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

import br.com.paywallet.exception.BusinessException;

/** Converts between API amounts (BigDecimal, BRL) and ledger amounts (long, cents). */
public final class Money {

    private Money() {
    }

    public static long toCents(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new BusinessException("Amount must have at most 2 decimal places");
        }
    }

    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
