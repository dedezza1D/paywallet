package br.com.paywallet.exception;

public class AccountClosedException extends RuntimeException {

    public AccountClosedException() {
        super("This account is closed");
    }
}
