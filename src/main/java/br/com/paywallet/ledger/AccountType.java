package br.com.paywallet.ledger;

import java.util.UUID;

public enum AccountType {
    USER_WALLET,
    /** Counterpart of money entering from outside (Pix-in, boleto, deposit). Allowed to go negative. */
    SYSTEM_CASH_IN,
    /** Platform revenue (MDR, installment interest). */
    SYSTEM_FEES;

    public static final UUID CASH_IN_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID FEES_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
}
