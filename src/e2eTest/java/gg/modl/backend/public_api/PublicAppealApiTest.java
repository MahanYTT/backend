package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import gg.modl.backend.support.TestDataProvider;
import gg.modl.backend.support.TestDatabase;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PublicAppealApiTest {

    static ApiClient api;

    private static String testUuid;
    private static int testTypeOrdinal;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPublicApiAvailable(), StagingCredentials.publicApiUnavailableReason());
        api = new ApiClient();

        testUuid = TestDataProvider.getPlayers().get(0).uuid();
        testTypeOrdinal = TestDataProvider.getPunishmentTypes().get(0).ordinal();
    }

    @Test
    void getAppeal() throws Exception {
        var response = api.publicGet("/v1/public/appeals/nonexistent-appeal-id");
        assertEquals(404, response.statusCode());
    }

    @Test
    void createAndGetAppeal() throws Exception {
        // Need an active punishment first
        var createPunishment = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "type_ordinal", testTypeOrdinal,
            "reason", "Public appeal test",
            "duration", 300,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        if (createPunishment.statusCode() != 200) {
            return;
        }
        String punishmentId = JsonHelper.parseObject(createPunishment.body()).get("punishmentId").getAsString();

        // Create appeal
        var createAppeal = api.publicPost("/v1/public/appeals", Map.of(
            "punishmentId", punishmentId,
            "playerUuid", testUuid,
            "email", "test@example.com",
            "reason", "API test appeal - auto cleanup"
        ));
        int appealStatus = createAppeal.statusCode();
        assertTrue(appealStatus == 200 || appealStatus == 201, "Expected 200 or 201 but got " + appealStatus);

        if (appealStatus == 200 || appealStatus == 201) {
            var json = JsonHelper.parseObject(createAppeal.body());
            String appealId = json.has("appealId") ? json.get("appealId").getAsString() : null;
            if (appealId != null) {
                // DB VERIFICATION: confirm appeal created as ticket with type=appeal
                if (TestDatabase.isAvailable()) {
                    var dbTicket = TestDatabase.getInstance().findTicketById(appealId);
                    assertNotNull(dbTicket, "Appeal should exist in DB (tickets collection) after creation");
                    assertEquals("appeal", dbTicket.getString("type"));
                }

                // Get the appeal
                var getResponse = api.publicGet("/v1/public/appeals/" + appealId);
                JsonHelper.assertStatus(getResponse, 200);

                // Cleanup: dismiss via panel
                api.panelPatch("/v1/panel/appeals/" + appealId + "/status", Map.of("status", "dismissed"));
            }
        }

        // Cleanup punishment
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "cleanup"
        ));
    }

    @Test
    void replyToAppeal() throws Exception {
        var response = api.publicPost("/v1/public/appeals/nonexistent-appeal-id/replies", Map.of(
            "name", "PublicUser",
            "content", "Test reply",
            "type", "player",
            "staff", false
        ));
        assertEquals(404, response.statusCode());
    }
}

