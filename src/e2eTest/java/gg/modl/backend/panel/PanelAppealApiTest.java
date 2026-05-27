package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PanelAppealApiTest {

    static ApiClient api;

    private static final String TEST_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void getAppealsByPunishment() throws Exception {
        // Create a punishment to look up
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", TEST_UUID,
            "issuerName", "TestBot",
            "type_ordinal", 14,
            "reason", "Panel Appeal test",
            "duration", 300,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        if (createResponse.statusCode() != 200) {
            return;
        }
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var response = api.panelGet("/v1/panel/appeals/punishment/" + punishmentId);
        // No appeals exist for a freshly created punishment
        assertEquals(404, response.statusCode());

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "cleanup"
        ));
    }

    @Test
    void getAppealById() throws Exception {
        // Test with a nonexistent ID to verify the route responds
        var response = api.panelGet("/v1/panel/appeals/nonexistent-appeal-id");
        assertEquals(404, response.statusCode(), "Expected 404 for nonexistent appeal");
    }

    @Test
    void replyToAppeal() throws Exception {
        // Need an existing appeal; test with nonexistent to verify route
        var response = api.panelPost("/v1/panel/appeals/nonexistent-appeal-id/replies", Map.of(
            "name", "TestBot",
            "content", "Test reply",
            "type", "staff",
            "staff", true
        ));
        assertEquals(404, response.statusCode(), "Expected 404 for nonexistent appeal");
    }

    @Test
    void updateAppealStatus() throws Exception {
        var response = api.panelPatch("/v1/panel/appeals/nonexistent-appeal-id/status", Map.of(
            "status", "dismissed"
        ));
        assertEquals(404, response.statusCode(), "Expected 404 for nonexistent appeal");
    }
}

