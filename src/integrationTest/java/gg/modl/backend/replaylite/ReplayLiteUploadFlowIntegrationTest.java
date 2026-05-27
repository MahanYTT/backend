package gg.modl.backend.replaylite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.replaylite.data.ReplayLiteDailyQuotaDocument;
import gg.modl.backend.replaylite.data.ReplayLiteDocument;
import gg.modl.backend.replaylite.data.ReplayLiteStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.support.AbstractMongoIntegrationTest;
import gg.modl.backend.support.AbstractS3IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test for the Replay Lite upload flow — the security-critical path
 * flagged in CLAUDE.md (replay upload + quota accounting). Exercises:
 *
 * <ul>
 *   <li>API-key authentication via {@code X-API-Key} through the real Spring Security chain
 *   <li>POST {@code /v1/replay-lite/replays/upload} → presigned PUT URL backed by LocalStack S3
 *   <li>A real HTTP PUT to that URL against LocalStack (so the presigned URL must validate)
 *   <li>POST {@code /v1/replay-lite/replays/{id}/confirm} → atomic PENDING→CONFIRMED transition
 *   <li>Daily quota document creation and increment
 *   <li>Idempotent confirm (second call returns conflict, quota does not double-count)
 * </ul>
 *
 * <p>Because the test composes both shared containers ({@link AbstractMongoIntegrationTest} for
 * Mongo + LocalStack S3), it has to inline the S3 {@link DynamicPropertySource} rather than
 * extend two base classes. The Mongo container is reused via the {@code MONGO_CONTAINER} static
 * inherited from {@link AbstractMongoIntegrationTest}; the LocalStack container is reused via
 * the static {@code LOCALSTACK_CONTAINER} on {@link AbstractS3IntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReplayLiteUploadFlowIntegrationTest extends AbstractMongoIntegrationTest {

    private static final String VALID_API_KEY = "replay-lite-integration-api-key";

    @DynamicPropertySource
    static void registerS3Properties(DynamicPropertyRegistry registry) {
        // Static-init the LocalStack container by referencing the abstract base class.
        // We can't extend both AbstractMongoIntegrationTest and AbstractS3IntegrationTest,
        // but the LocalStack container itself is static and shared once touched.
        AbstractS3IntegrationTest.LOCALSTACK_CONTAINER.start();
        String endpoint = AbstractS3IntegrationTest.LOCALSTACK_CONTAINER
            .getEndpointOverride(org.testcontainers.containers.localstack.LocalStackContainer.Service.S3)
            .toString();
        registry.add("modl.replay-lite.storage.key-id",
            AbstractS3IntegrationTest.LOCALSTACK_CONTAINER::getAccessKey);
        registry.add("modl.replay-lite.storage.application-key",
            AbstractS3IntegrationTest.LOCALSTACK_CONTAINER::getSecretKey);
        registry.add("modl.replay-lite.storage.bucket-name",
            () -> AbstractS3IntegrationTest.REPLAY_LITE_BUCKET);
        registry.add("modl.replay-lite.storage.endpoint", () -> endpoint);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private gg.modl.backend.database.mongo.TenantMongoAccess tenantMongoAccess;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private gg.modl.backend.server.ServerService serverService;

    private MongoTemplate mongoTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void seedServer() {
        mongoTemplate = tenantMongoAccess.global();
        mongoTemplate.dropCollection(CollectionName.MODL_SERVERS);
        mongoTemplate.dropCollection(CollectionName.REPLAY_LITE_REPLAYS);
        mongoTemplate.dropCollection(CollectionName.REPLAY_LITE_DAILY_QUOTAS);

        Server server = new Server("Demo", "demo", null, "admin@example.com", true, ServerPlan.FREE);
        server.setApiKey(VALID_API_KEY);
        mongoTemplate.save(server);
        serverService.evictAllServerCaches();
    }

    @Test
    void uploadFlowPresignsPutsAndConfirmsAgainstMongoAndS3() throws Exception {
        byte[] replayBytes = "modl-replay-test-bytes".getBytes(StandardCharsets.UTF_8);

        // 1. initUpload — server-side issues presigned PUT URL for LocalStack.
        MvcResult initResult = mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/upload")
                .header(RequestHeader.API_KEY, VALID_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "requestedSize": %d,
                      "mcVersion": "1.21.0"
                    }""".formatted(replayBytes.length)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.replayId").isNotEmpty())
            .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
            .andReturn();

        JsonNode initBody = objectMapper.readTree(initResult.getResponse().getContentAsString());
        String replayId = initBody.get("replayId").asText();
        String uploadUrl = initBody.get("uploadUrl").asText();
        assertThat(uploadUrl).contains(AbstractS3IntegrationTest.REPLAY_LITE_BUCKET);

        // Mongo: PENDING record exists, no quota row yet.
        assertThat(findReplay(replayId).getStatus()).isEqualTo(ReplayLiteStatus.PENDING);
        assertThat(countQuotaDocs()).isZero();

        // 2. Client PUTs to the presigned URL on LocalStack S3.
        HttpResponse<Void> putResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(replayBytes))
                .build(),
            HttpResponse.BodyHandlers.discarding());
        assertThat(putResponse.statusCode()).isBetween(200, 299);

        // 3. confirmUpload — atomic PENDING → CONFIRMED, quota incremented.
        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/" + replayId + "/confirm")
                .header(RequestHeader.API_KEY, VALID_API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        ReplayLiteDocument confirmed = findReplay(replayId);
        assertThat(confirmed.getStatus()).isEqualTo(ReplayLiteStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedSize()).isEqualTo((long) replayBytes.length);
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        assertThat(confirmed.getExpiresAt()).isNotNull();

        assertThat(countQuotaDocs()).isEqualTo(1L);
    }

    @Test
    void confirmIsIdempotentAndDoesNotDoubleCountQuota() throws Exception {
        byte[] bytes = new byte[256];
        String replayId = initAndPut(bytes);

        // First confirm — succeeds, increments quota to 1.
        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/" + replayId + "/confirm")
                .header(RequestHeader.API_KEY, VALID_API_KEY))
            .andExpect(status().isOk());
        assertThat(quotaCountFor(replayId)).isEqualTo(1);

        // Second confirm — the document is no longer PENDING after the first confirm, so
        // requirePending() raises ValidationException → 400. Quota row must not double-count.
        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/" + replayId + "/confirm")
                .header(RequestHeader.API_KEY, VALID_API_KEY))
            .andExpect(status().isBadRequest());

        // Quota row is unchanged.
        assertThat(quotaCountFor(replayId)).isEqualTo(1);
    }

    @Test
    void confirmingBeforePutReturnsNotFoundBecauseS3HeadFails() throws Exception {
        // initUpload creates the PENDING document and presigns a URL, but the client never PUTs.
        // confirmUpload then asks S3 for HEAD on the object; LocalStack returns 404, so the
        // service raises ResourceNotFoundException → 404 to the client.
        MvcResult initResult = mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/upload")
                .header(RequestHeader.API_KEY, VALID_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "requestedSize": 128,
                      "mcVersion": "1.21.0"
                    }"""))
            .andExpect(status().isOk())
            .andReturn();
        String replayId = objectMapper.readTree(initResult.getResponse().getContentAsString())
            .get("replayId").asText();

        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/" + replayId + "/confirm")
                .header(RequestHeader.API_KEY, VALID_API_KEY))
            .andExpect(status().isNotFound());

        // PENDING row remains; nothing in the quota.
        assertThat(findReplay(replayId).getStatus()).isEqualTo(ReplayLiteStatus.PENDING);
        assertThat(countQuotaDocs()).isZero();
    }

    @Test
    void confirmingPutLargerThanRequestedSizeRejectsAndDoesNotConfirm() throws Exception {
        // Client declares one size but actually PUTs more bytes. confirmUpload reads the real
        // size from S3 HEAD and rejects via ValidationException → 400. The PENDING document
        // must NOT transition to CONFIRMED.
        int requestedSize = 64;
        byte[] oversized = new byte[requestedSize + 64];

        MvcResult initResult = mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/upload")
                .header(RequestHeader.API_KEY, VALID_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "requestedSize": %d,
                      "mcVersion": "1.21.0"
                    }""".formatted(requestedSize)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = objectMapper.readTree(initResult.getResponse().getContentAsString());
        String replayId = body.get("replayId").asText();
        String uploadUrl = body.get("uploadUrl").asText();

        // LocalStack accepts the oversized PUT regardless of the presigned content-length —
        // the size guard is server-side in confirmUpload, not in S3.
        HttpResponse<Void> putResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(oversized))
                .build(),
            HttpResponse.BodyHandlers.discarding());
        assertThat(putResponse.statusCode()).isBetween(200, 299);

        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/" + replayId + "/confirm")
                .header(RequestHeader.API_KEY, VALID_API_KEY))
            .andExpect(status().isBadRequest());

        assertThat(findReplay(replayId).getStatus()).isEqualTo(ReplayLiteStatus.PENDING);
        assertThat(countQuotaDocs()).isZero();
    }

    @Test
    void confirmingAStalePendingReplayReturnsBadRequest() throws Exception {
        // Init + PUT a small replay, then push the document's createdAt back > 15 minutes
        // so requireNotStale() in the service rejects with ValidationException.
        String replayId = initAndPut(new byte[32]);

        java.util.Date staleCreatedAt = java.util.Date.from(java.time.Instant.now().minusSeconds(16 * 60));
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(replayId)),
            new org.springframework.data.mongodb.core.query.Update().set("createdAt", staleCreatedAt),
            ReplayLiteDocument.class,
            CollectionName.REPLAY_LITE_REPLAYS);

        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/" + replayId + "/confirm")
                .header(RequestHeader.API_KEY, VALID_API_KEY))
            .andExpect(status().isBadRequest());

        assertThat(findReplay(replayId).getStatus()).isEqualTo(ReplayLiteStatus.PENDING);
        assertThat(countQuotaDocs()).isZero();
    }

    @Test
    void confirmingAnotherServersReplayReturnsNotFound() throws Exception {
        // Seed a second server with its own API key, then have the first server PUT a replay.
        Server other = new Server("Other", "other", null, "other@example.com", true, ServerPlan.FREE);
        other.setApiKey("other-api-key");
        mongoTemplate.save(other);

        String replayId = initAndPut(new byte[64]);

        // The "other" server attempts to confirm a replay it did not initiate.
        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/" + replayId + "/confirm")
                .header(RequestHeader.API_KEY, "other-api-key"))
            .andExpect(status().isNotFound());

        // Original PENDING row is untouched.
        assertThat(findReplay(replayId).getStatus()).isEqualTo(ReplayLiteStatus.PENDING);
    }

    private String initAndPut(byte[] bytes) throws Exception {
        MvcResult initResult = mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/upload")
                .header(RequestHeader.API_KEY, VALID_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "requestedSize": %d,
                      "mcVersion": "1.21.0"
                    }""".formatted(bytes.length)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = objectMapper.readTree(initResult.getResponse().getContentAsString());
        String replayId = body.get("replayId").asText();
        String uploadUrl = body.get("uploadUrl").asText();

        HttpResponse<Void> putResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(),
            HttpResponse.BodyHandlers.discarding());
        assertThat(putResponse.statusCode()).isBetween(200, 299);
        return replayId;
    }

    private ReplayLiteDocument findReplay(String replayId) {
        return mongoTemplate.findOne(
            Query.query(Criteria.where("_id").is(replayId)),
            ReplayLiteDocument.class,
            CollectionName.REPLAY_LITE_REPLAYS);
    }

    private long countQuotaDocs() {
        return mongoTemplate.count(new Query(), ReplayLiteDailyQuotaDocument.class, CollectionName.REPLAY_LITE_DAILY_QUOTAS);
    }

    private int quotaCountFor(String replayId) {
        ReplayLiteDailyQuotaDocument doc = mongoTemplate.findOne(
            Query.query(Criteria.where("replayIds").is(replayId)),
            ReplayLiteDailyQuotaDocument.class,
            CollectionName.REPLAY_LITE_DAILY_QUOTAS);
        return doc == null ? 0 : doc.getCount();
    }
}
