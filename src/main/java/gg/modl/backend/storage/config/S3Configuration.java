package gg.modl.backend.storage.config;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@ConfigurationProperties(prefix = "modl.storage")
@Validated
@Getter
@Setter
public class S3Configuration {
    private String keyId = "";
    private String applicationKey = "";
    private String endpoint = "";
    private String bucketName = "";
    private String cdnDomain = "";

    @Bean
    public S3Client s3Client() {
        if (!isConfigured()) {
            return null;
        }
        return S3Client.builder()
            .credentialsProvider(StaticCredentialsProvider.create(buildCredentials()))
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        if (!isConfigured()) {
            return null;
        }
        return S3Presigner.builder()
            .credentialsProvider(StaticCredentialsProvider.create(buildCredentials()))
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .build();
    }

    private boolean isConfigured() {
        return !keyId.isBlank() && !applicationKey.isBlank() && !endpoint.isBlank();
    }

    private AwsBasicCredentials buildCredentials() {
        return AwsBasicCredentials.create(keyId, applicationKey);
    }
}
