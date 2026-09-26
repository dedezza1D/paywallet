package br.com.paywallet.ledger;

public enum LedgerTransactionType {
    P2P_TRANSFER,
    CASH_IN,
    PIX_INTERNAL,
    PIX_OUT,
    PIX_OUT_REVERSAL,
    PIX_IN,
    CHARGE_PAYMENT,
    BILL_PAYMENT,
    BILL_PAYMENT_REVERSAL,
    YIELD_CREDIT,
    LOAN_DISBURSEMENT,
    LOAN_INSTALLMENT_PAYMENT
}
