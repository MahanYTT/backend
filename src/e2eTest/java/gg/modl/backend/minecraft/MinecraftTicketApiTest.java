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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftTicketApiTest {

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
    void createAndCleanupTicket() throws Exception {
        var createResponse = api.minecraftPost("/v1/minecraft/tickets", Map.of(
            "creatorUuid", testUuid,
            "creatorName", testUsername,
            "type", "bug_report",
            "subject", "API Test Ticket - auto cleanup",
            "description", "This ticket was created by an automated API test."
        ));
        JsonHelper.assertStatus(createResponse, 200);
        var json = JsonHelper.parseObject(createResponse.body());
        assertTrue(json.has("ticketId"));
        String ticketId = json.get("ticketId").getAsString();

        // DB VERIFICATION: confirm ticket exists in MongoDB
        if (TestDatabase.isAvailable()) {
            var dbTicket = TestDatabase.getInstance().findTicketById(ticketId);
            assertNotNull(dbTicket, "Ticket should exist in DB after creation");
            assertEquals("bug", dbTicket.getString("type"));
        }

        // Cleanup: close via panel
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of(
            "status", "closed"
        ));
    }

    @Test
    void createUnfinishedTicket() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/tickets/unfinished", Map.of(
            "creatorUuid", testUuid,
            "creatorName", testUsername,
            "type", "bug_report"
        ));
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("ticketId"));

        // Cleanup
        String ticketId = json.get("ticketId").getAsString();
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void listTickets() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/tickets?limit=5");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("tickets"));
    }

    @Test
    void getTicketById() throws Exception {
        var createResponse = api.minecraftPost("/v1/minecraft/tickets", Map.of(
            "creatorUuid", testUuid,
            "creatorName", testUsername,
            "type", "bug_report",
            "subject", "API Test - get by id"
        ));
        String ticketId = JsonHelper.parseObject(createResponse.body()).get("ticketId").getAsString();

        var response = api.minecraftGet("/v1/minecraft/tickets/" + ticketId);
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("ticket"));

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void getTicketsByPlayer() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/tickets/player/" + testUuid);
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("tickets"));
    }

    @Test
    void claimTicket() throws Exception {
        var createResponse = api.minecraftPost("/v1/minecraft/tickets", Map.of(
            "creatorUuid", testUuid,
            "creatorName", testUsername,
            "type", "bug_report",
            "subject", "API Test - claim"
        ));
        String ticketId = JsonHelper.parseObject(createResponse.body()).get("ticketId").getAsString();

        var claimResponse = api.minecraftPost("/v1/minecraft/tickets/" + ticketId + "/claim", Map.of(
            "playerUuid", testUuid,
            "playerName", testUsername
        ));
        int claimStatus = claimResponse.statusCode();
        // 200 if claimed, 409 if ticket already linked to a player via creation
        assertTrue(claimStatus == 200 || claimStatus == 409, "Expected 200 or 409 but got " + claimStatus);

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }
}
