package br.com.paywallet.external;

/** Transactional email (verification codes, security notices). */
public interface EmailSender {

    /** @throws org.springframework.mail.MailException when the message cannot be handed to the mail server */
    void send(String to, String subject, String text);
}
