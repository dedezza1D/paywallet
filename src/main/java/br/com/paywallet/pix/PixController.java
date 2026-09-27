package br.com.paywallet.pix;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.auth.RequiresTransactionPin;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.pix.PixDtos.PixKeyResponse;
import br.com.paywallet.pix.PixDtos.PixPaymentResponse;
import br.com.paywallet.pix.PixDtos.QrCodeResponse;
import br.com.paywallet.pix.PixDtos.RegisterKeyRequest;
import br.com.paywallet.pix.PixDtos.SendPixRequest;
import br.com.paywallet.pix.PixDtos.StaticQrCodeRequest;
import br.com.paywallet.pix.PixKeyService.KeyOwner;
import br.com.paywallet.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/pix")
public class PixController {

    private final PixKeyService keys;
    private final PixService pix;
    private final UserService users;
    private final PixProperties props;

    public PixController(PixKeyService keys, PixService pix, UserService users, PixProperties props) {
        this.keys = keys;
        this.pix = pix;
        this.users = users;
        this.props = props;
    }

    @PostMapping("/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public PixKeyResponse registerKey(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegisterKeyRequest req) {
        return PixKeyResponse.from(keys.register(userId(jwt), req.type(), req.value()));
    }

    @GetMapping("/keys")
    public List<PixKeyResponse> listKeys(@AuthenticationPrincipal Jwt jwt) {
        return keys.list(userId(jwt)).stream().map(PixKeyResponse::from).toList();
    }

    @DeleteMapping("/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteKey(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID keyId) {
        keys.delete(userId(jwt), keyId);
    }

    /** Shows who receives a payment before the payer confirms it. */
    @GetMapping("/keys/lookup")
    public KeyOwner lookup(@AuthenticationPrincipal Jwt jwt, @RequestParam String key) {
        return keys.owner(userId(jwt), key);
    }

    @PostMapping("/qr-codes")
    public QrCodeResponse staticQrCode(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StaticQrCodeRequest req) {
        var user = users.get(userId(jwt));
        String key = PixKeyType.detect(req.key()).normalize(req.key());
        keys.findLocal(key)
                .filter(k -> k.getUserId().equals(user.getId()))
                .orElseThrow(() -> new BusinessException("QR codes can only be generated for your own Pix keys"));
        var code = new BrCode(key, req.value(), req.description(), user.getFullName(), props.merchantCity(), req.txid());
        return new QrCodeResponse(code.encode());
    }

    /** 201 when settled immediately (same institution), 202 while an external Pix is being settled. */
    @RequiresTransactionPin
    @PostMapping("/payments")
    public ResponseEntity<PixPaymentResponse> send(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String idempotencyKey,
            @Valid @RequestBody SendPixRequest req) {
        var result = pix.send(userId(jwt), req, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.payment());
        }
        var status = result.payment().status() == PixPayment.Status.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.payment());
    }

    @GetMapping("/payments/{endToEndId}")
    public PixPaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String endToEndId) {
        return pix.get(userId(jwt), endToEndId);
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
