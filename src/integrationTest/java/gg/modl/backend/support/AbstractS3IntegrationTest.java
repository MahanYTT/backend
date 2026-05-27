package gg.modl.backend.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * LocalStack S3 for integration tests. Provides the same surface as Backblaze B2 (S3-compatible)
 * for tests that need real PUT / GET / presigned URL semantics — anything that mocks
 * {@code S3Client} cannot catch presign-validity, header-signing, or bucket-existence bugs.
 *
 * <p>Two buckets are pre-created to mirror production: {@code TENANT_BUCKET} (general tenant
 * storage) and {@code REPLAY_LITE_BUCKET} (Replay Lite uploads).
 */
@ActiveProfiles("test")
public abstract class AbstractS3IntegrationTest {

    public static final String TENANT_BUCKET = "modl-it-tenant";
    public static final String REPLAY_LITE_BUCKET = "modl-it-replay-lite";

    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:3.8");

    public static final LocalStackContainer LOCALSTACK_CONTAINER = new LocalStackContainer(LOCALSTACK_IMAGE)
        .withServices(Service.S3)
        .withReuse(true);

    static {
        LOCALSTACK_CONTAINER.start();
        ensureBucket(TENANT_BUCKET);
        ensureBucket(REPLAY_LITE_BUCKET);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String endpoint = LOCALSTACK_CONTAINER.getEndpointOverride(Service.S3).toString();
        registry.add("modl.storage.key-id", LOCALSTACK_CONTAINER::getAccessKey);
        registry.add("modl.storage.application-key", LOCALSTACK_CONTAINER::getSecretKey);
        registry.add("modl.storage.bucket-name", () -> TENANT_BUCKET);
        registry.add("modl.storage.endpoint", () -> endpoint);
        registry.add("modl.replay-lite.storage.key-id", LOCALSTACK_CONTAINER::getAccessKey);
        registry.add("modl.replay-lite.storage.application-key", LOCALSTACK_CONTAINER::getSecretKey);
        registry.add("modl.replay-lite.storage.bucket-name", () -> REPLAY_LITE_BUCKET);
        registry.add("modl.replay-lite.storage.endpoint", () -> endpoint);
    }

    protected static S3Client buildLocalStackS3Client() {
        return S3Client.builder()
            .endpointOverride(LOCALSTACK_CONTAINER.getEndpointOverride(Service.S3))
            .region(Region.of(LOCALSTACK_CONTAINER.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK_CONTAINER.getAccessKey(), LOCALSTACK_CONTAINER.getSecretKey())))
            .forcePathStyle(true)
            .build();
    }

    private static void ensureBucket(String bucket) {
        try (S3Client client = buildLocalStackS3Client()) {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException ignored) {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            }
        }
    }
}
