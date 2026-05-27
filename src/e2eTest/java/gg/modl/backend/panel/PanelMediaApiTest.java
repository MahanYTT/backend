package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PanelMediaApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void getConfig() throws Exception {
        var response = api.panelGet("/v1/panel/media/config");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("backblazeConfigured") || json.has("supportedTypes"));
    }

    @Test
    void presignUpload() throws Exception {
        var response = api.panelPost("/v1/panel/media/presign", Map.of(
            "fileName", "test-image.png",
            "contentType", "image/png",
            "fileSize", 1024,
            "uploadType", "ticket"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void confirmUpload() throws Exception {
        // This requires a valid key from a completed upload, so test with invalid key
        var response = api.panelPost("/v1/panel/media/confirm", Map.of(
            "key", "nonexistent-key"
        ));
        assertEquals(403, response.statusCode());
    }

    @Test
    void deleteMedia() throws Exception {
        // Test with nonexistent key
        var response = api.panelDelete("/v1/panel/media/nonexistent-test-key");
        assertEquals(403, response.statusCode());
    }
}

