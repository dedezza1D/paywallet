package br.com.paywallet.exception;

public class InvalidTransactionPinException extends RuntimeException {

    public InvalidTransactionPinException() {
        super("Invalid transaction PIN");
    }
}
