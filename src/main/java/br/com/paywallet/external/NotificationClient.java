package br.com.paywallet.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import br.com.paywallet.config.ExternalServicesProperties;

/** The notification service is unreliable: retries a few times, and a failure never undoes the transfer. */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final String url;

    public NotificationClient(RestClient externalRestClient, ExternalServicesProperties props) {
        this.restClient = externalRestClient;
        this.url = props.notifierUrl();
    }

    public boolean send(String email, String message) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restClient.post().uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new NotificationRequest(email, message))
                        .retrieve()
                        .toBodilessEntity();
                return true;
            } catch (RestClientException e) {
                log.warn("Failed to notify {} (attempt {}/{}): {}", email, attempt, MAX_ATTEMPTS, e.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
        log.error("Notification to {} dropped after {} attempts", email, MAX_ATTEMPTS);
        return false;
    }

    private static void sleepBeforeRetry(int attempt) {
        if (attempt == MAX_ATTEMPTS) {
            return;
        }
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    record NotificationRequest(String email, String message) {
    }
}
