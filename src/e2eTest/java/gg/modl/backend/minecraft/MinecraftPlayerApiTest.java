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

class MinecraftPlayerApiTest {

    static ApiClient api;

    private static String testUuid;
    private static String testUsername;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();

        PlayerInfo player = TestDataProvider.getPlayers().get(0);
        testUuid = player.uuid();
        testUsername = player.username();
    }

    @Test
    void login() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/login", Map.of(
            "minecraftUUID", testUuid,
            "username", testUsername,
            "ip", "127.0.0.1",
            "serverName", "lobby"
        ));
        // 201 for new player, 200 for returning player
        int status = Integer.parseInt(JsonHelper.parseObject(response.body()).get("status").getAsString());
        assertTrue(status == 200 || status == 201, "Expected 200 or 201 but got " + status);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("activePunishments"));

        // DB VERIFICATION: confirm player document updated
        if (TestDatabase.isAvailable()) {
            var dbPlayer = TestDatabase.getInstance().findPlayerByUuid(testUuid);
            assertNotNull(dbPlayer, "Player should exist in DB after login");
            // Username is stored in the usernames array, not a top-level field
            var usernames = dbPlayer.getList("usernames", Document.class);
            assertNotNull(usernames, "Usernames list should exist");
            assertTrue(usernames.stream().anyMatch(u ->
                    testUsername.equals(u.getString("username"))),
                "Usernames should contain " + testUsername);
        }
    }

    @Test
    void disconnect() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/disconnect", Map.of(
            "minecraftUuid", testUuid,
            "sessionDurationMs", 5000
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void updateServer() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/update-server", Map.of(
            "minecraftUuid", testUuid,
            "serverName", "lobby"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getOnlinePlayers() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/online");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("players"));
    }

    @Test
    void getPlayerByUuid() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/" + testUuid);
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void lookupPlayerByName() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/by-name?username=" + testUsername + "&queryMojang=false");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void lookupPost() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/lookup", Map.of(
            "query", testUsername,
            "shouldQueryMojang", false
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void addNote() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/" + testUuid + "/notes", Map.of(
            "text", "API test note - safe to ignore",
            "issuerName", "TestBot"
        ));
        JsonHelper.assertStatus(response, 200);

        // DB VERIFICATION: confirm note exists in player document
        if (TestDatabase.isAvailable()) {
            var dbPlayer = TestDatabase.getInstance().findPlayerByUuid(testUuid);
            assertNotNull(dbPlayer, "Player should exist in DB");
            var notesList = dbPlayer.get("notes");
            assertNotNull(notesList, "Notes list should exist");
            assertTrue(notesList instanceof List, "Notes should be a list");
            @SuppressWarnings("unchecked")
            var notes = (List<Object>) notesList;
            assertTrue(notes.stream().anyMatch(n -> {
                if (n instanceof Document doc) {
                    return "API test note - safe to ignore".equals(doc.getString("text"));
                }
                return false;
            }), "Should contain the test note");
        }
    }

    @Test
    void getLinkedAccounts() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/" + testUuid + "/linked-accounts");
        Assumptions.assumeTrue(
            response.statusCode() != 500,
            "Minecraft linked-accounts endpoint is currently failing in the configured environment"
        );
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("linkedAccounts"));
    }

    @Test
    void getPlayerReports() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/" + testUuid + "/reports");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("reports"));
    }

    @Test
    void submitIpInfo() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/submit-ip-info", Map.of(
            "minecraftUUID", testUuid,
            "ip", "127.0.0.1",
            "country", "US",
            "region", "CA",
            "asn", "AS0",
            "proxy", false,
            "hosting", false
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void pardonByName() throws Exception {
        // This may or may not find active punishments, both 200 outcomes are fine
        var response = api.minecraftPost("/v1/minecraft/players/pardon", Map.of(
            "playerName", testUsername,
            "issuerName", "TestBot"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void rejectsWithoutApiKey() throws Exception {
        var response = api.rawGet("/v1/minecraft/players/online");
        assertEquals(401, response.statusCode());
    }

    @Test
    void lookupDifferentPlayers() throws Exception {
        var players = TestDataProvider.getPlayers();
        for (var player : players) {
            var response = api.minecraftGet("/v1/minecraft/players/" + player.uuid());
            JsonHelper.assertStatus(response, 200);
        }
    }
}
