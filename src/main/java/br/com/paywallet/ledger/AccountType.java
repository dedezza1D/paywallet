package br.com.paywallet.ledger;

import java.util.UUID;

public enum AccountType {
    USER_WALLET,
    /** Counterpart of money entering from outside (Pix-in, boleto, deposit). Allowed to go negative. */
    SYSTEM_CASH_IN,
    /** Platform revenue (MDR, installment interest). */
    SYSTEM_FEES,
    /** Counterpart of Pix exchanged with other institutions. Allowed to go negative. */
    SYSTEM_PIX_SETTLEMENT,
    /** Bill payments owed to the clearing system until settled with the beneficiary's bank. */
    SYSTEM_BILL_SETTLEMENT,
    /** Return on the assets backing customer balances; pays the daily yield. Allowed to go negative. */
    SYSTEM_YIELD;

    public static final UUID CASH_IN_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID FEES_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID PIX_SETTLEMENT_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID BILL_SETTLEMENT_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    public static final UUID YIELD_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
}
