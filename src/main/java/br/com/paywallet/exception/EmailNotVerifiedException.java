package br.com.paywallet.exception;

public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("Email not verified. Enter the code sent to your email or request a new one.");
    }
}
