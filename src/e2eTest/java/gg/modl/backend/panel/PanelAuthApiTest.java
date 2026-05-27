package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PanelAuthApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Disabled("Skipped: would send a real email code to staging user")
    @Test
    void sendEmailCode() throws Exception {}

    @Disabled("Skipped: requires valid email code")
    @Test
    void verifyEmailCode() throws Exception {}

    @Disabled("Skipped: would invalidate the test session token")
    @Test
    void logout() throws Exception {}

    @Test
    void getMe() throws Exception {
        var response = api.panelGet("/v1/panel/auth/me");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("id") || json.has("email"));
    }

    @Test
    void getPermissions() throws Exception {
        var response = api.panelGet("/v1/panel/auth/permissions");
        JsonHelper.assertStatus(response, 200);
        // Response is a JSON array of permission strings
        var arr = JsonHelper.parseArray(response.body());
        assertNotNull(arr);
    }

    @Test
    void updateProfile() throws Exception {
        // Get current profile first
        var meResponse = api.panelGet("/v1/panel/auth/me");
        var me = JsonHelper.parseObject(meResponse.body());
        String currentUsername = me.has("username") ? me.get("username").getAsString() : "test";

        // Update with same values (idempotent)
        var response = api.panelPatch("/v1/panel/auth/profile", Map.of(
            "username", currentUsername
        ));
        JsonHelper.assertStatus(response, 200);
    }
}

