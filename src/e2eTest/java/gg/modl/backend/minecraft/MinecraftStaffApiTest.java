package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftStaffApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();
    }

    @Test
    void listStaff() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/staff");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("staff"));
    }

    @Test
    void getPermissions() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/staff/permissions");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("data"));
    }

    @Test
    void updateStaffRole() throws Exception {
        // Get a staff member to update
        var listResponse = api.minecraftGet("/v1/minecraft/staff");
        var json = JsonHelper.parseObject(listResponse.body());
        var staff = json.getAsJsonArray("staff");
        if (staff.isEmpty()) {
            return;
        }

        // Just read; don't actually mutate roles in staging
        String staffId = staff.get(0).getAsJsonObject().get("id").getAsString();
        String currentRole = staff.get(0).getAsJsonObject().get("role").getAsString();

        // Set to same role (idempotent)
        var response = api.minecraftPatch("/v1/minecraft/staff/" + staffId + "/role", java.util.Map.of(
            "role", currentRole
        ));
        JsonHelper.assertStatus(response, 200);
    }
}
