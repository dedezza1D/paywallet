package br.com.paywallet.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

/**
 * Field-level encryption for personal data (CPF/CNPJ, phone numbers). Values become
 * {@code enc:v1:<key id>:base64(iv || ciphertext || tag)} with AES-256-GCM and a random IV. A blind index, the
 * HMAC-SHA256 of the value under a separate key, supports equality lookups and unique constraints without
 * decrypting anything.
 */
@Component
public class FieldCipher {

    static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyRing keys;

    FieldCipher(KeyRing keys) {
        this.keys = keys;
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        String keyId = keys.activeDataKeyId();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.dataKey(keyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    /** Values written before encryption was introduced are returned as they are. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        int separator = stored.indexOf(':', PREFIX.length());
        String keyId = stored.substring(PREFIX.length(), separator);
        byte[] data = Base64.getDecoder().decode(stored.substring(separator + 1));
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.dataKey(keyId), new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field decryption failed", e);
        }
    }

    public String blindIndex(String plain) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(keys.indexKey());
            return HexFormat.of().formatHex(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Blind index failed", e);
        }
    }
}
