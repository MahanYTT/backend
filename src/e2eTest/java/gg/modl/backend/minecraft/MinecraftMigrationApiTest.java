package gg.modl.backend.minecraft;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class MinecraftMigrationApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();
    }

    @Disabled("Skipped: migration upload requires multipart file upload which changes staging state")
    @Test
    void uploadMigrationFile() throws Exception {
        // Would require multipart upload - skipped for safety
    }

    @Disabled("Skipped: migration progress report requires valid migration state on staging")
    @Test
    void reportProgress() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/migration/progress", Map.of(
            "status", "IDLE",
            "message", "API test progress report",
            "processed", 0,
            "total", 0
        ));
        JsonHelper.assertStatus(response, 200);
    }
}
