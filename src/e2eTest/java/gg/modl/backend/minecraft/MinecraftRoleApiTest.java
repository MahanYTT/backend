package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftRoleApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();
    }

    @Test
    void listRoles() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/roles");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("roles"));
    }

    @Test
    void getRoleById() throws Exception {
        // Get a role ID from list
        var listResponse = api.minecraftGet("/v1/minecraft/roles");
        var roles = JsonHelper.parseObject(listResponse.body()).getAsJsonArray("roles");
        if (roles.isEmpty()) {
            return;
        }

        String roleId = roles.get(0).getAsJsonObject().get("id").getAsString();
        var response = api.minecraftGet("/v1/minecraft/roles/" + roleId);
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("role"));
    }

    @Test
    void updateRolePermissions() throws Exception {
        // Get a role to update (idempotent - set same permissions)
        var listResponse = api.minecraftGet("/v1/minecraft/roles");
        var roles = JsonHelper.parseObject(listResponse.body()).getAsJsonArray("roles");
        if (roles.isEmpty()) {
            return;
        }

        var role = roles.get(0).getAsJsonObject();
        String roleId = role.get("id").getAsString();
        var permissions = role.getAsJsonArray("permissions");

        // Convert to List<String>
        List<String> permList = new java.util.ArrayList<>();
        permissions.forEach(p -> permList.add(p.getAsString()));

        var response = api.minecraftPatch("/v1/minecraft/roles/" + roleId + "/permissions", Map.of(
            "permissions", permList
        ));
        JsonHelper.assertStatus(response, 200);
    }
}
