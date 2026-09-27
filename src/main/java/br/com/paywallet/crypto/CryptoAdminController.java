package br.com.paywallet.crypto;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin only, through the {@code /admin/**} rule of the security configuration. */
@RestController
@RequestMapping("/admin/crypto/keys")
public class CryptoAdminController {

    public record RotationResult(KeyRing.KeyInfo activeKey, int reencrypted) {
    }

    private final KeyRing keys;
    private final PiiEncryption encryption;

    public CryptoAdminController(KeyRing keys, PiiEncryption encryption) {
        this.keys = keys;
        this.encryption = encryption;
    }

    @GetMapping
    public List<KeyRing.KeyInfo> keys() {
        return keys.keys();
    }

    /** Activates a new data key and re-encrypts existing personal data with it. */
    @PostMapping("/rotation")
    public RotationResult rotate() {
        var active = keys.rotateDataKey();
        return new RotationResult(active, encryption.reencryptAll());
    }
}
