package br.com.paywallet.exception;

/** Answered with 428 Precondition Required: the client must ask the user for the PIN and repeat the request. */
public class TransactionPinRequiredException extends RuntimeException {

    public TransactionPinRequiredException(String message) {
        super(message);
    }
}
