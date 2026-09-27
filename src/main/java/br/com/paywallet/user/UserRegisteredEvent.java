package br.com.paywallet.user;

/** Published inside the sign-up transaction; listeners that send email must wait for the commit. */
public record UserRegisteredEvent(Long userId, String email) {
}
