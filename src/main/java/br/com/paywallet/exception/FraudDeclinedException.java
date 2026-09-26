package br.com.paywallet.exception;

/** The risk engine refused the operation. The rules stay internal so they cannot be probed. */
public class FraudDeclinedException extends RuntimeException {

    public FraudDeclinedException() {
        super("Operation blocked by risk analysis. Contact support if you did not expect this.");
    }
}
