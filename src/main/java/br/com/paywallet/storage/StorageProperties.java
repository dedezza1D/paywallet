package br.com.paywallet.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint             empty = real AWS S3; set = S3-compatible endpoint used by the application
 * @param publicEndpoint       host used in presigned URLs, i.e. what the mobile app or browser can reach
 * @param accessKey            empty = default AWS credentials chain (IAM role, env, profile)
 * @param serverSideEncryption e.g. "aws:kms" or "AES256"; empty = bucket default
 * @param kmsKeyId             KMS key used when serverSideEncryption is "aws:kms"
 * @param createBucket         create the bucket on startup (local environments only)
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String endpoint,
        String publicEndpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        String serverSideEncryption,
        String kmsKeyId,
        boolean createBucket,
        Duration presignTtl,
        long maxUploadBytes) {
}
