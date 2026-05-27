package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PublicMediaApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPublicApiAvailable(), StagingCredentials.publicApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void getConfig() throws Exception {
        var response = api.publicGet("/v1/public/media/config");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("backblazeConfigured") || json.has("supportedTypes"));
    }

    @Test
    void presignUpload() throws Exception {
        var response = api.publicPost("/v1/public/media/presign", Map.of(
            "fileName", "test-image.png",
            "contentType", "image/png",
            "fileSize", 1024,
            "uploadType", "ticket",
            "entityId", "new"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void confirmUpload() throws Exception {
        var response = api.publicPost("/v1/public/media/confirm", Map.of(
            "key", "nonexistent-key"
        ));
        assertEquals(403, response.statusCode());
    }
}

