package br.com.paywallet.exception;

/** Same message for unknown, expired, revoked and reused tokens, so callers learn nothing from it. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid or expired session. Please log in again.");
    }
}
