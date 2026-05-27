package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PanelStorageApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void getQuota() throws Exception {
        var response = api.panelGet("/v1/panel/storage/quota");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void listFiles() throws Exception {
        var response = api.panelGet("/v1/panel/storage/files");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("files"));
    }

    @Test
    void listFilesWithPrefix() throws Exception {
        var response = api.panelGet("/v1/panel/storage/files?prefix=test");
        JsonHelper.assertStatus(response, 200);
    }

    @Disabled("Skipped: test user lacks storage download permission")
    @Test
    void downloadFile() throws Exception {
        // Get a file key first
        var listResponse = api.panelGet("/v1/panel/storage/files");
        var json = JsonHelper.parseObject(listResponse.body());
        var files = json.getAsJsonArray("files");
        if (files.isEmpty()) {
            return;
        }

        String key = files.get(0).getAsJsonObject().get("key").getAsString();
        var response = api.panelGet("/v1/panel/storage/download/" + key);
        JsonHelper.assertStatus(response, 200);
    }
}

