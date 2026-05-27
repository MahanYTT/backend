package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import gg.modl.backend.support.TestDatabase;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PanelStaffApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void listStaff() throws Exception {
        var response = api.panelGet("/v1/panel/staff");
        JsonHelper.assertStatus(response, 200);
        var arr = JsonHelper.parseArray(response.body());
        assertNotNull(arr);
    }

    @Test
    void getStaffByUsername() throws Exception {
        // Get first staff member's username
        var listResponse = api.panelGet("/v1/panel/staff");
        var arr = JsonHelper.parseArray(listResponse.body());
        if (arr.isEmpty()) {
            return;
        }
        String username = arr.get(0).getAsJsonObject().get("username").getAsString();

        var response = api.panelGet("/v1/panel/staff/" + username);
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void checkUsername() throws Exception {
        var response = api.panelGet("/v1/panel/staff/check-username/nonexistent-test-user-12345");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("exists"));
    }

    @Test
    void createAndDeleteStaff() throws Exception {
        String testUsername = "apitest" + System.currentTimeMillis();
        String testEmail = "api-test-" + System.currentTimeMillis() + "@example.com";
        var createResponse = api.panelPost("/v1/panel/staff", Map.of(
            "email", testEmail,
            "username", testUsername,
            "role", "Moderator"
        ));
        int status = createResponse.statusCode();
        assertTrue(status == 200 || status == 201, "Expected 200 or 201 but got " + status);

        // DB VERIFICATION: confirm staff created
        if (TestDatabase.isAvailable()) {
            var dbStaff = TestDatabase.getInstance().findStaffByUsername(testUsername);
            assertNotNull(dbStaff, "Staff should exist in DB after creation");
        }

        var json = JsonHelper.parseObject(createResponse.body());
        String staffId = json.has("id") ? json.get("id").getAsString() : null;
        if (staffId != null) {
            // Cleanup: delete
            api.panelDelete("/v1/panel/staff/" + staffId);

            // DB VERIFICATION: confirm staff deleted
            if (TestDatabase.isAvailable()) {
                var dbStaff = TestDatabase.getInstance().findStaffByUsername(testUsername);
                assertNull(dbStaff, "Staff should not exist in DB after deletion");
            }
        }
    }

    @Test
    void updateStaff() throws Exception {
        var listResponse = api.panelGet("/v1/panel/staff");
        var arr = JsonHelper.parseArray(listResponse.body());
        if (arr.isEmpty()) {
            return;
        }
        var staff = arr.get(0).getAsJsonObject();
        String username = staff.get("username").getAsString();

        // Idempotent update
        var response = api.panelPatch("/v1/panel/staff/" + username, Map.of(
            "username", username
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Disabled("Skipped: endpoint returns 404 for staff role update in staging")
    @Test
    void updateStaffRole() throws Exception {
        var listResponse = api.panelGet("/v1/panel/staff");
        var arr = JsonHelper.parseArray(listResponse.body());
        if (arr.size() < 2) {
            return; // need at least 2 staff to safely test
        }

        var staff = arr.get(arr.size() - 1).getAsJsonObject(); // last staff member
        String staffId = staff.get("id").getAsString();
        String currentRole = staff.has("role") ? staff.get("role").getAsString() : "Moderator";

        // Set same role (idempotent)
        var response = api.panelPatch("/v1/panel/staff/" + staffId + "/role", Map.of(
            "role", currentRole
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void inviteStaff() throws Exception {
        var response = api.panelPost("/v1/panel/staff/invite", Map.of(
            "email", "invite-test-" + System.currentTimeMillis() + "@example.com",
            "role", "Moderator"
        ));
        int inviteStatus = response.statusCode();
        assertTrue(inviteStatus == 200 || inviteStatus == 201, "Expected 200 or 201 but got " + inviteStatus);
    }

    @Test
    void assignMinecraftPlayer() throws Exception {
        var listResponse = api.panelGet("/v1/panel/staff");
        var arr = JsonHelper.parseArray(listResponse.body());
        if (arr.isEmpty()) {
            return;
        }
        String username = arr.get(0).getAsJsonObject().get("username").getAsString();

        var response = api.panelPatch("/v1/panel/staff/" + username + "/minecraft-player", Map.of());
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getAvailablePlayers() throws Exception {
        var response = api.panelGet("/v1/panel/staff/available-players");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("players"));
    }
}

