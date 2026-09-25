package br.com.paywallet.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.external")
public record ExternalServicesProperties(
        String authorizerUrl,
        String notifierUrl,
        Duration connectTimeout,
        Duration readTimeout) {
}
