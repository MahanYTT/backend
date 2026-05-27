package gg.modl.backend.panel;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import gg.modl.backend.support.TestDataProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PanelAnalyticsApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
        TestDataProvider.getPlayers(); // triggers DB cleanup of corrupted data
    }

    @Test
    void getOverview() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/overview");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getTicketAnalytics() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/tickets?period=30d");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getPunishmentAnalytics() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/punishments?period=30d");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getAuditLogs() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/audit-logs?period=7d");
        JsonHelper.assertStatus(response, 200);
    }

    @Disabled("Endpoint deprecated: returns 501, use /audit/staff-performance instead")
    @Test
    void getStaffPerformance() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/staff-performance?period=30d");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getPlayerActivity() throws Exception {
        var response = api.panelGet("/v1/panel/analytics/player-activity?period=30d");
        JsonHelper.assertStatus(response, 200);
    }
}

