package br.com.paywallet.pix;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.paywallet.pix.PixDtos.IncomingPix;
import br.com.paywallet.pix.PixDtos.PixPaymentResponse;
import jakarta.validation.Validator;

/**
 * Called by the PSP, not by users, so it is authenticated by an HMAC signature instead of a JWT. The raw body
 * is read as a string because the signature covers the exact bytes that were sent.
 */
@RestController
public class PixWebhookController {

    private final PixService pix;
    private final WebhookSignatureVerifier signatures;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public PixWebhookController(PixService pix, WebhookSignatureVerifier signatures, ObjectMapper objectMapper,
                                Validator validator) {
        this.pix = pix;
        this.signatures = signatures;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping("/pix/webhooks/incoming")
    public ResponseEntity<?> incoming(@RequestHeader(value = "X-Pix-Timestamp", required = false) String timestamp,
                                      @RequestHeader(value = "X-Pix-Signature", required = false) String signature,
                                      @RequestBody String body) {
        if (!signatures.isValid(timestamp, signature, body)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid webhook signature"));
        }
        IncomingPix incoming;
        try {
            incoming = objectMapper.readValue(body, IncomingPix.class);
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed payload"));
        }
        var violations = validator.validate(incoming);
        if (!violations.isEmpty()) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
            problem.setProperty("errors", violations.stream().collect(Collectors.toMap(
                    v -> v.getPropertyPath().toString(), v -> v.getMessage(), (a, b) -> a)));
            return ResponseEntity.badRequest().body(problem);
        }
        PixPaymentResponse payment = pix.receive(incoming);
        return ResponseEntity.ok(payment);
    }
}
