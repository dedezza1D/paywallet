package br.com.paywallet.marketplace;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for gift card codes, which work like cash for whoever holds them. Output is
 * {@code base64(iv || ciphertext || tag)} with a random 96-bit IV per value.
 */
@Component
class VoucherCipher {

    private static final Logger log = LoggerFactory.getLogger(VoucherCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    VoucherCipher(MarketplaceProperties props) {
        if (props.voucherKey() == null || props.voucherKey().isBlank()) {
            log.warn("app.marketplace.voucher-key is not set: using an ephemeral key. "
                    + "Gift card codes stored now cannot be read after a restart");
            this.key = generateKey();
        } else {
            byte[] raw = Base64.getDecoder().decode(props.voucherKey());
            if (raw.length != 32) {
                throw new IllegalStateException("app.marketplace.voucher-key must be 32 bytes (base64)");
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Voucher encryption failed", e);
        }
    }

    String decrypt(String encoded) {
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Voucher decryption failed", e);
        }
    }

    private static SecretKey generateKey() {
        try {
            var generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
