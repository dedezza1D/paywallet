package br.com.paywallet.card;

import java.time.Clock;
import java.util.function.Function;
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

import br.com.paywallet.card.CardDtos.AuthorizationRequest;
import br.com.paywallet.card.CardDtos.ClearingRequest;
import br.com.paywallet.card.CardDtos.ReversalRequest;
import br.com.paywallet.external.HmacSignatures;
import jakarta.validation.Validator;

/**
 * Called by the card processor, authenticated by an HMAC signature of the raw body instead of a user token.
 * Authorizations are answered synchronously: the processor approves or declines the purchase with the returned code.
 */
@RestController
public class CardWebhookController {

    private static final String TIMESTAMP_HEADER = "X-Card-Timestamp";
    private static final String SIGNATURE_HEADER = "X-Card-Signature";

    private final CardAuthorizationService authorizations;
    private final CardProperties props;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final Clock clock;

    public CardWebhookController(CardAuthorizationService authorizations, CardProperties props,
                                 ObjectMapper objectMapper, Validator validator, Clock clock) {
        this.authorizations = authorizations;
        this.props = props;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.clock = clock;
    }

    @PostMapping("/cards/webhooks/authorizations")
    public ResponseEntity<?> authorize(@RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
                                       @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                       @RequestBody String body) {
        return handle(timestamp, signature, body, AuthorizationRequest.class, authorizations::authorize);
    }

    @PostMapping("/cards/webhooks/clearings")
    public ResponseEntity<?> clear(@RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
                                   @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                   @RequestBody String body) {
        return handle(timestamp, signature, body, ClearingRequest.class, authorizations::clear);
    }

    @PostMapping("/cards/webhooks/reversals")
    public ResponseEntity<?> reverse(@RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
                                     @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                     @RequestBody String body) {
        return handle(timestamp, signature, body, ReversalRequest.class, authorizations::reverse);
    }

    private <T> ResponseEntity<?> handle(String timestamp, String signature, String body, Class<T> type,
                                         Function<T, ?> action) {
        if (!HmacSignatures.isValid(props.webhookSecret(), props.webhookTolerance(), clock.instant(), timestamp,
                signature, body)) {
            return problem(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        T payload;
        try {
            payload = objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            return problem(HttpStatus.BAD_REQUEST, "Malformed payload");
        }
        var violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
            problem.setProperty("errors", violations.stream().collect(Collectors.toMap(
                    v -> v.getPropertyPath().toString(), v -> v.getMessage(), (a, b) -> a)));
            return ResponseEntity.badRequest().body(problem);
        }
        return ResponseEntity.ok(action.apply(payload));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
