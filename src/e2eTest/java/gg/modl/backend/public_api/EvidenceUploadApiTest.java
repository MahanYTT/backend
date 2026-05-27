package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import gg.modl.backend.support.TestDataProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EvidenceUploadApiTest {

    static ApiClient api;

    private static String testUuid;
    private static int testTypeOrdinal;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPublicApiAvailable(), StagingCredentials.publicApiUnavailableReason());
        api = new ApiClient();

        testUuid = TestDataProvider.getPlayers().get(0).uuid();
        testTypeOrdinal = TestDataProvider.getPunishmentTypes().get(0).ordinal();
    }

    @Test
    void validateToken() throws Exception {
        // Invalid token should return 404
        var response = api.publicGet("/v1/public/evidence-upload/invalid-token");
        assertEquals(404, response.statusCode());
    }

    @Test
    void validateTokenWithRealToken() throws Exception {
        // Create a punishment and get an upload token
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "Evidence upload test",
            "duration", 300,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        if (createResponse.statusCode() != 200) {
            return;
        }
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var tokenResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/upload-token", Map.of(
            "issuerName", "TestBot"
        ));
        if (tokenResponse.statusCode() != 200) {
            api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
                "issuerName", "TestBot", "reason", "cleanup"
            ));
            return;
        }
        String token = JsonHelper.parseObject(tokenResponse.body()).get("token").getAsString();

        // Validate the token
        var response = api.publicGet("/v1/public/evidence-upload/" + token);
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("punishmentId"));

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot", "reason", "cleanup"
        ));
    }

    @Test
    void presignUpload() throws Exception {
        var response = api.publicPost("/v1/public/evidence-upload/invalid-token/presign", Map.of(
            "fileName", "test.png",
            "contentType", "image/png",
            "fileSize", 1024
        ));
        assertEquals(404, response.statusCode());
    }

    @Test
    void confirmUpload() throws Exception {
        var response = api.publicPost("/v1/public/evidence-upload/invalid-token/confirm", Map.of(
            "key", "nonexistent-key"
        ));
        assertEquals(404, response.statusCode());
    }

    @Test
    void submitEvidence() throws Exception {
        var response = api.publicPost("/v1/public/evidence-upload/invalid-token/submit", Map.of(
            "evidence", List.of(Map.of(
                "url", "https://example.com/evidence.png",
                "fileName", "evidence.png",
                "fileType", "image/png",
                "fileSize", 1024
            ))
        ));
        assertEquals(404, response.statusCode());
    }
}

