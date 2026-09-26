package br.com.paywallet.pix;

import java.time.Clock;

import org.springframework.stereotype.Component;

import br.com.paywallet.external.HmacSignatures;

@Component
class WebhookSignatureVerifier {

    private final PixProperties props;
    private final Clock clock;

    WebhookSignatureVerifier(PixProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    boolean isValid(String timestamp, String signature, String body) {
        return HmacSignatures.isValid(props.webhookSecret(), props.webhookTolerance(), clock.instant(), timestamp,
                signature, body);
    }

    static String sign(String secret, String payload) {
        return HmacSignatures.sign(secret, payload);
    }
}
