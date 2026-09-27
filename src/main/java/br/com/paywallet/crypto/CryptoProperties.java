package br.com.paywallet.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param kmsEndpoint     custom KMS endpoint (LocalStack in development); empty = AWS
 * @param masterKeyAlias  alias of the KMS key that encrypts the data keys
 * @param createMasterKey creates the master key and alias when missing (development only)
 */
@ConfigurationProperties(prefix = "app.crypto")
public record CryptoProperties(String kmsEndpoint, String region, String accessKey, String secretKey,
                               String masterKeyAlias, boolean createMasterKey) {
}
