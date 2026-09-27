package br.com.paywallet.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Encrypts an entity attribute on write and decrypts it on read. Created by Spring, through Hibernate. */
@Converter
public class EncryptedString implements AttributeConverter<String, String> {

    private final FieldCipher cipher;

    public EncryptedString(FieldCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String column) {
        return cipher.decrypt(column);
    }
}
