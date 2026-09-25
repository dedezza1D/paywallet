package br.com.paywallet.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException user(Long id) {
        return new NotFoundException("User %d not found".formatted(id));
    }
}
