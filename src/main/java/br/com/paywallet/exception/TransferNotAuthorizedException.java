package br.com.paywallet.exception;

public class TransferNotAuthorizedException extends RuntimeException {

    public TransferNotAuthorizedException() {
        super("Transfer denied by the authorization service");
    }
}
