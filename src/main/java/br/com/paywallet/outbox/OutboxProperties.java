package br.com.paywallet.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param pollInterval delay between relay runs
 * @param batchSize    maximum events relayed per run
 * @param sendTimeout  maximum wait for the broker to acknowledge one event
 * @param retention    how long published events are kept before cleanup
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize, Duration sendTimeout, Duration retention) {
}
