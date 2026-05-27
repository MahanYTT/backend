package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftDashboardApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();
    }

    @Test
    void getDashboardStats() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/dashboard/stats");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("stats"));
        var stats = json.getAsJsonObject("stats");
        assertTrue(stats.has("totalPlayers"));
        assertTrue(stats.has("onlinePlayers"));
        assertTrue(stats.has("activeBans"));
    }
}
