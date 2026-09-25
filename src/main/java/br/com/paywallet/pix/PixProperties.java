package br.com.paywallet.pix;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ispb                 this institution's ISPB, used in end-to-end ids
 * @param institutionName      shown to payers when a key belongs to this institution
 * @param merchantCity         city written into generated QR codes
 * @param settlementInterval   delay between runs of the outgoing settlement worker
 * @param webhookSecret        HMAC secret shared with the PSP; empty disables the incoming webhook
 * @param webhookTolerance     maximum clock skew accepted on webhook timestamps
 * @param lookupsPerMinute     key owner lookups allowed per user per minute (anti-enumeration)
 * @param maxKeysIndividual    key limit for individuals (BCB rule)
 * @param maxKeysMerchant      key limit for merchants (BCB rule)
 */
@ConfigurationProperties(prefix = "app.pix")
public record PixProperties(
        String ispb,
        String institutionName,
        String merchantCity,
        Duration settlementInterval,
        String webhookSecret,
        Duration webhookTolerance,
        int lookupsPerMinute,
        int maxKeysIndividual,
        int maxKeysMerchant) {
}
