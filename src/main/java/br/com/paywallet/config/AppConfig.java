package br.com.paywallet.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    /** Microsecond ticks match Postgres precision, so a value returned now equals the one read back later. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Short timeouts: a slow external service must not hold up transfers. */
    @Bean
    RestClient externalRestClient(RestClient.Builder builder, ExternalServicesProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return builder.requestFactory(factory).build();
    }
}
