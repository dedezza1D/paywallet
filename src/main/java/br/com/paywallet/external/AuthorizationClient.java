package br.com.paywallet.external;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import br.com.paywallet.config.ExternalServicesProperties;
import br.com.paywallet.exception.ExternalServiceException;

/** Expected response: {"status":"success","data":{"authorization":true}}, or HTTP 403 when denied. */
@Component
public class AuthorizationClient {

    private final RestClient restClient;
    private final String url;

    public AuthorizationClient(RestClient externalRestClient, ExternalServicesProperties props) {
        this.restClient = externalRestClient;
        this.url = props.authorizerUrl();
    }

    public boolean isAuthorized() {
        try {
            var response = restClient.get().uri(url).retrieve().body(AuthorizationResponse.class);
            return response != null && response.data() != null && response.data().authorization();
        } catch (HttpClientErrorException.Forbidden e) {
            return false;
        } catch (RestClientException e) {
            throw new ExternalServiceException("Authorization service unavailable", e);
        }
    }

    record AuthorizationResponse(String status, Data data) {
        record Data(boolean authorization) {
        }
    }
}
