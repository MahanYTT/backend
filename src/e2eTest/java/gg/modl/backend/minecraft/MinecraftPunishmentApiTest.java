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
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftPunishmentApiTest {

    static ApiClient api;

    private static String testUuid;
    private static String testUsername;
    private static int testTypeOrdinal;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();

        PlayerInfo player = TestDataProvider.getPlayerWithPunishments();
        testUuid = player.uuid();
        testUsername = player.username();
        testTypeOrdinal = TestDataProvider.getPunishmentTypes().get(0).ordinal();
    }

    @Test
    void createAndPardonPunishment() throws Exception {
        // Create
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - auto cleanup",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        var createJson = JsonHelper.parseObject(createResponse.body());
        assertTrue(createJson.has("punishmentId"), "Response should contain punishmentId");
        String punishmentId = createJson.get("punishmentId").getAsString();

        // DB VERIFICATION: confirm punishment exists in MongoDB
        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist in DB after creation");
            assertEquals("TestBot", dbPunishment.getString("issuerName"));
        }

        // Cleanup: pardon it
        var pardonResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
        JsonHelper.assertStatus(pardonResponse, 200);

        // DB VERIFICATION: confirm pardon modification exists
        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should still exist in DB after pardon");
            var mods = dbPunishment.getList("modifications", Document.class);
            assertNotNull(mods, "Modifications list should exist");
            assertTrue(mods.stream().anyMatch(m -> {
                String type = m.getString("type");
                return "PARDON".equals(type) || "MANUAL_PARDON".equals(type);
            }), "Should have a PARDON modification");
        }
    }

    @Test
    void createWithLegacyEndpoint() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/punishments/create", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test legacy - auto cleanup",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(response, 200);

        // Pardon via player pardon endpoint to clean up
        api.minecraftPost("/v1/minecraft/players/pardon", Map.of(
            "playerName", testUsername,
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void getPunishmentById() throws Exception {
        // Create one first
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - get by id",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        // Get by ID
        var getResponse = api.minecraftGet("/v1/minecraft/punishments/" + punishmentId);
        JsonHelper.assertStatus(getResponse, 200);
        var json = JsonHelper.parseObject(getResponse.body());
        assertTrue(json.has("punishment"));

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void getUploadToken() throws Exception {
        // Create punishment
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - upload token",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var tokenResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/upload-token", Map.of(
            "issuerName", "TestBot"
        ));
        JsonHelper.assertStatus(tokenResponse, 200);
        var json = JsonHelper.parseObject(tokenResponse.body());
        assertTrue(json.has("token"));

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void getRecentPunishments() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/punishments/recent?hours=48");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("punishments"));
    }

    @Test
    void previewPunishment() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/punishments/preview?playerUuid=" + testUuid + "&typeOrdinal=" + testTypeOrdinal);
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("success"));
    }

    @Test
    void acknowledgePunishment() throws Exception {
        // Create punishment
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - acknowledge",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var ackResponse = api.minecraftPost("/v1/minecraft/punishments/acknowledge", Map.of(
            "punishmentId", punishmentId,
            "playerUuid", testUuid,
            "executedAt", "2025-01-01T00:00:00Z",
            "success", true
        ));
        JsonHelper.assertStatus(ackResponse, 200);

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void addNoteToPunishment() throws Exception {
        // Create punishment
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - add note",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var noteResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/note", Map.of(
            "issuerName", "TestBot",
            "note", "API test note"
        ));
        JsonHelper.assertStatus(noteResponse, 200);

        // DB VERIFICATION: confirm note exists
        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist in DB");
            var notes = dbPunishment.getList("notes", Document.class);
            assertNotNull(notes, "Notes list should exist");
            assertTrue(notes.stream().anyMatch(n -> "API test note".equals(n.getString("note"))
                                                    || "API test note".equals(n.getString("text"))),
                "Should contain the test note");
        }

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void addEvidenceToPunishment() throws Exception {
        // Create punishment
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - add evidence",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var evidenceResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/evidence", Map.of(
            "issuerName", "TestBot",
            "evidenceUrl", "https://example.com/evidence.png"
        ));
        JsonHelper.assertStatus(evidenceResponse, 200);

        // DB VERIFICATION: confirm evidence exists
        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist in DB");
            var evidence = dbPunishment.getList("evidence", Document.class);
            assertNotNull(evidence, "Evidence list should exist");
            assertTrue(evidence.stream().anyMatch(e ->
                    "https://example.com/evidence.png".equals(e.getString("url"))
                    || "https://example.com/evidence.png".equals(e.getString("evidenceUrl"))),
                "Should contain the test evidence URL");
        }

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void updatePunishmentDuration() throws Exception {
        // Create punishment
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - duration update",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var durationResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/duration", Map.of(
            "issuerName", "TestBot",
            "newDuration", 120
        ));
        JsonHelper.assertStatus(durationResponse, 200);

        // DB VERIFICATION: confirm duration change modification
        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist in DB");
            var mods = dbPunishment.getList("modifications", Document.class);
            assertNotNull(mods, "Modifications list should exist");
            assertTrue(mods.stream().anyMatch(m -> {
                String type = m.getString("type");
                return "DURATION_CHANGE".equals(type) || "MANUAL_DURATION_CHANGE".equals(type);
            }), "Should have a DURATION_CHANGE modification");
        }

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void togglePunishmentOption() throws Exception {
        // Create punishment
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "API Test - toggle option",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var toggleResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/toggle", Map.of(
            "issuerName", "TestBot",
            "option", "ALT_BLOCKING",
            "enabled", true
        ));
        JsonHelper.assertStatus(toggleResponse, 200);

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void createPunishmentWithEachType() throws Exception {
        var types = TestDataProvider.getPunishmentTypes();
        for (var type : types) {
            var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
                "targetUuid", testUuid,
                "issuerName", "TestBot",
                "type_ordinal", type.ordinal(),
                "reason", "API Test - type " + type.name(),
                "duration", 60,
                "severity", "LOW",
                "status", "ACTIVE"
            ));
            JsonHelper.assertStatus(createResponse, 200);
            String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

            // DB VERIFICATION
            if (TestDatabase.isAvailable()) {
                var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
                assertNotNull(dbPunishment, "Punishment with type " + type.name() + " should exist in DB");
            }

            // Cleanup
            api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
                "issuerName", "TestBot",
                "reason", "API test cleanup"
            ));
        }
    }

    @Test
    void createPunishmentForDifferentPlayers() throws Exception {
        var players = TestDataProvider.getPlayers();
        for (var player : players) {
            var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
                "targetUuid", player.uuid(),
                "issuerName", "TestBot",
                "type_ordinal", testTypeOrdinal,
                "reason", "API Test - player " + player.username(),
                "duration", 60,
                "severity", "LOW",
                "status", "ACTIVE"
            ));
            JsonHelper.assertStatus(createResponse, 200);
            String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

            // DB VERIFICATION
            if (TestDatabase.isAvailable()) {
                var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(player.uuid(), punishmentId);
                assertNotNull(dbPunishment, "Punishment for " + player.username() + " should exist in DB");
            }

            // Cleanup
            api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
                "issuerName", "TestBot",
                "reason", "API test cleanup"
            ));
        }
    }
}
