package gg.modl.backend.panel;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PanelMigrationApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void getMigrationStatus() throws Exception {
        var response = api.panelGet("/v1/panel/migration/status");
        JsonHelper.assertStatus(response, 200);
    }

    @Disabled("Skipped: would start a real migration on staging")
    @Test
    void startMigration() throws Exception {}

    @Disabled("Skipped: no active migration to cancel on staging")
    @Test
    void cancelMigration() throws Exception {
        var response = api.panelPost("/v1/panel/migration/cancel", Map.of());
        JsonHelper.assertStatus(response, 200);
    }
}

