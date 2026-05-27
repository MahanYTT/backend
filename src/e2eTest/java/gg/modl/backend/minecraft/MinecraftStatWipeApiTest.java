package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import gg.modl.backend.support.TestDataProvider;
import gg.modl.backend.support.TestDataProvider.PlayerInfo;
import gg.modl.backend.support.TestDatabase;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftStatWipeApiTest {

    static ApiClient api;
    private static String testUuid;
    private static int testTypeOrdinal;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();

        PlayerInfo player = TestDataProvider.getPlayerWithPunishments();
        testUuid = player.uuid();
        testTypeOrdinal = TestDataProvider.getPunishmentTypes().get(0).ordinal();
    }

    @Test
    void statWipeAcknowledgeSetsCompletedFlag() throws Exception {
        // Create a punishment with wipeAfterExpiry enabled
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - stat wipe acknowledge",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE",
            "data", Map.of("wipeAfterExpiry", true)
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        // Acknowledge the stat wipe
        var ackResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/stat-wipe-acknowledge", Map.of(
            "punishmentId", punishmentId,
            "serverName", "TestServer",
            "success", true
        ));
        JsonHelper.assertStatus(ackResponse, 200);
        var ackJson = JsonHelper.parseObject(ackResponse.body());
        assertTrue(ackJson.has("success"));

        // DB VERIFICATION: confirm statWipeCompleted is set
        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist in DB");
            var data = dbPunishment.get("data", Document.class);
            assertNotNull(data, "Punishment data should exist");
            assertTrue(data.getBoolean("statWipeCompleted", false),
                "statWipeCompleted should be true after acknowledgement");
            assertNotNull(data.get("statWipeCompletedAt"),
                "statWipeCompletedAt should be set");
        }

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void statWipeAcknowledgeReturnsNotFoundForInvalidId() throws Exception {
        var ackResponse = api.minecraftPost("/v1/minecraft/punishments/nonexistent-id/stat-wipe-acknowledge", Map.of(
            "punishmentId", "nonexistent-id",
            "serverName", "TestServer",
            "success", true
        ));
        JsonHelper.assertStatus(ackResponse, 404);
    }

    @Test
    void statWipeAcknowledgeIgnoresWhenWipeDisabled() throws Exception {
        // Create punishment WITHOUT wipeAfterExpiry
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - stat wipe disabled",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        // Acknowledge should succeed but not set the flag since wipeAfterExpiry is not enabled
        var ackResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/stat-wipe-acknowledge", Map.of(
            "punishmentId", punishmentId,
            "serverName", "TestServer",
            "success", true
        ));
        JsonHelper.assertStatus(ackResponse, 200);
        var ackJson = JsonHelper.parseObject(ackResponse.body());
        assertEquals("Stat wipe no longer enabled for this punishment", ackJson.get("message").getAsString());

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void syncResponseIncludesPendingStatWipesField() throws Exception {
        // Verify the sync response now includes the pendingStatWipes field
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
        assertTrue(json.has("data"));
        var data = json.getAsJsonObject("data");
        assertTrue(data.has("pendingStatWipes"), "Sync response data should include pendingStatWipes field");
    }

    @Test
    void toggleStatWipeThenAcknowledge() throws Exception {
        // Create a punishment without wipeAfterExpiry
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - toggle then ack",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        // Enable stat wipe via toggle
        var toggleResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/toggle", Map.of(
            "issuerName", "TestBot",
            "option", "STAT_WIPE",
            "enabled", true
        ));
        JsonHelper.assertStatus(toggleResponse, 200);

        // Now acknowledge should work and set the flag
        var ackResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/stat-wipe-acknowledge", Map.of(
            "punishmentId", punishmentId,
            "serverName", "TestServer",
            "success", true
        ));
        JsonHelper.assertStatus(ackResponse, 200);

        // DB VERIFICATION
        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist");
            var data = dbPunishment.get("data", Document.class);
            assertTrue(data.getBoolean("wipeAfterExpiry", false), "wipeAfterExpiry should be true");
            assertTrue(data.getBoolean("statWipeCompleted", false), "statWipeCompleted should be true");
        }

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }
}
