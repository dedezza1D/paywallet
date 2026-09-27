package br.com.paywallet.crypto;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

@Configuration
class KmsConfig {

    @Bean(destroyMethod = "close")
    KmsClient kmsClient(CryptoProperties props) {
        var builder = KmsClient.builder().region(Region.of(props.region()));
        builder.credentialsProvider(StringUtils.hasText(props.accessKey())
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()))
                : DefaultCredentialsProvider.create());
        if (StringUtils.hasText(props.kmsEndpoint())) {
            builder.endpointOverride(URI.create(props.kmsEndpoint()));
        }
        return builder.build();
    }
}
