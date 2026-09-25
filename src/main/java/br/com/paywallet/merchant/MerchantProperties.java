package br.com.paywallet.merchant;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param walletFeeBps       MDR in basis points for charges paid with wallet balance
 * @param pixFeeBps          MDR in basis points for charges paid with Pix
 * @param paymentLinkBaseUrl prefix of the public payment link; the charge token is appended
 * @param defaultChargeTtl   validity of a charge when the merchant does not set one
 * @param maxChargeTtl       longest validity a merchant may set
 * @param zone               time zone used to group dashboard figures by day
 */
@ConfigurationProperties(prefix = "app.merchant")
public record MerchantProperties(
        int walletFeeBps,
        int pixFeeBps,
        String paymentLinkBaseUrl,
        Duration defaultChargeTtl,
        Duration maxChargeTtl,
        ZoneId zone) {
}
