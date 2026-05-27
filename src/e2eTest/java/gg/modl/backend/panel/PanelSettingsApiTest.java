package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PanelSettingsApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    // -- Punishment Types --

    @Test
    void getPunishmentTypes() throws Exception {
        var response = api.panelGet("/v1/panel/settings/punishment-types");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getPunishmentTypeByOrdinal() throws Exception {
        var response = api.panelGet("/v1/panel/settings/punishment-types/0");
        JsonHelper.assertStatus(response, 200);
    }

    @Disabled("Skipped: would reset punishment types to defaults on staging")
    @Test
    void resetPunishmentTypes() throws Exception {}

    // -- General Settings --

    @Test
    void getGeneralSettings() throws Exception {
        var response = api.panelGet("/v1/panel/settings/general");
        // Test user is not super admin, expects 403
        assertEquals(403, response.statusCode());
    }

    // -- Status Thresholds --

    @Test
    void getStatusThresholds() throws Exception {
        var response = api.panelGet("/v1/panel/settings/status-thresholds");
        JsonHelper.assertStatus(response, 200);
    }

    // -- API Keys --

    @Test
    void checkApiKeyExists() throws Exception {
        var response = api.panelGet("/v1/panel/settings/api-keys/minecraft/exists");
        // Test user is not super admin, expects 403
        assertEquals(403, response.statusCode());
    }

    @Test
    void revealApiKey() throws Exception {
        var response = api.panelGet("/v1/panel/settings/api-keys/minecraft/reveal");
        // Test user is not super admin, expects 403
        assertEquals(403, response.statusCode());
    }

    @Disabled("Skipped: would rotate the real API key on staging")
    @Test
    void generateApiKey() throws Exception {}

    // -- AI Moderation --

    @Test
    void getAiModerationSettings() throws Exception {
        var response = api.panelGet("/v1/panel/settings/ai-moderation");
        JsonHelper.assertStatus(response, 200);
    }

    // -- Webhooks --

    @Test
    void getWebhookSettings() throws Exception {
        var response = api.panelGet("/v1/panel/settings/webhooks");
        JsonHelper.assertStatus(response, 200);
    }

    // -- Ticket Forms --

    @Test
    void getTicketForms() throws Exception {
        var response = api.panelGet("/v1/panel/settings/ticket-forms");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getTicketFormByType() throws Exception {
        var response = api.panelGet("/v1/panel/settings/ticket-forms/bug_report");
        // Ticket form type may not exist on staging
        assertEquals(404, response.statusCode());
    }

    // -- Domain Settings --

    @Test
    void getDomainSettings() throws Exception {
        var response = api.panelGet("/v1/panel/settings/domain");
        JsonHelper.assertStatus(response, 200);
    }

    // -- Quick Responses --

    @Test
    void getQuickResponses() throws Exception {
        var response = api.panelGet("/v1/panel/settings/quick-responses");
        JsonHelper.assertStatus(response, 200);
    }
}

