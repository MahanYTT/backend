package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftSyncApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();
    }

    @Test
    void sync() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/sync", Map.of(
            "lastSyncTimestamp", "2025-01-01T00:00:00Z",
            "onlinePlayers", List.of(),
            "serverStatus", Map.of(
                "onlinePlayerCount", 0,
                "maxPlayers", 100,
                "serverVersion", "1.21",
                "timestamp", System.currentTimeMillis()
            )
        ));
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("timestamp"));
        assertTrue(json.has("data"));
    }
}
