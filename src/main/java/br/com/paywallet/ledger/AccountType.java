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
    SYSTEM_YIELD,
    /** Principal owed by borrowers (negative while loans are outstanding). */
    SYSTEM_LOAN_PRINCIPAL,
    /** Interest and late charges earned on loans. */
    SYSTEM_INTEREST_INCOME,
    /** IOF withheld from customers until paid to the government. */
    SYSTEM_TAX_PAYABLE,
    /** Debit card money reserved at authorization until cleared or released. */
    SYSTEM_CARD_HOLDS,
    /** Owed to the card network for cleared purchases. */
    SYSTEM_CARD_SETTLEMENT,
    /** What credit card holders owe (negative while there is debt). */
    SYSTEM_CARD_RECEIVABLES;

    public static final UUID CASH_IN_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID FEES_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID PIX_SETTLEMENT_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID BILL_SETTLEMENT_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    public static final UUID YIELD_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    public static final UUID LOAN_PRINCIPAL_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    public static final UUID INTEREST_INCOME_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    public static final UUID TAX_PAYABLE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    public static final UUID CARD_HOLDS_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
    public static final UUID CARD_SETTLEMENT_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    public static final UUID CARD_RECEIVABLES_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
}
