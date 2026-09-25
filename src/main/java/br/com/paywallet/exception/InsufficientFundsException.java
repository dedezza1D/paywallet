package br.com.paywallet.exception;

public class InsufficientFundsException extends BusinessException {

    public InsufficientFundsException() {
        super("Insufficient funds");
    }
}
