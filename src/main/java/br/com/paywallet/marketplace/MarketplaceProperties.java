package br.com.paywallet.marketplace;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param fulfillmentInterval delay between runs of the worker that delivers pending orders
 * @param voucherKey          base64 AES-256 key that encrypts gift card codes; empty = ephemeral key, development only
 * @param zone                time zone of the monthly cashback total
 */
@ConfigurationProperties(prefix = "app.marketplace")
public record MarketplaceProperties(Duration fulfillmentInterval, String voucherKey, ZoneId zone) {
}
