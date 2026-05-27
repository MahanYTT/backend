package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import gg.modl.backend.support.TestDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PanelRoleApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void listRoles() throws Exception {
        var response = api.panelGet("/v1/panel/roles");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("roles"));
    }

    @Test
    void getPermissions() throws Exception {
        var response = api.panelGet("/v1/panel/roles/permissions");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("permissions") || json.has("categories"));
    }

    @Test
    void getRoleById() throws Exception {
        var listResponse = api.panelGet("/v1/panel/roles");
        var roles = JsonHelper.parseObject(listResponse.body()).getAsJsonArray("roles");
        if (roles.isEmpty()) {
            return;
        }

        String roleId = roles.get(0).getAsJsonObject().get("id").getAsString();
        var response = api.panelGet("/v1/panel/roles/" + roleId);
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void createAndDeleteRole() throws Exception {
        String roleName = "API Test Role " + System.currentTimeMillis();
        var createResponse = api.panelPost("/v1/panel/roles", Map.of(
            "name", roleName,
            "description", "Created by automated API test",
            "permissions", List.of()
        ));
        int status = createResponse.statusCode();
        assertTrue(status == 200 || status == 201, "Expected 200 or 201 but got " + status);

        var json = JsonHelper.parseObject(createResponse.body());
        String roleId = null;
        if (json.has("role")) {
            roleId = json.getAsJsonObject("role").get("id").getAsString();
        } else if (json.has("id")) {
            roleId = json.get("id").getAsString();
        }

        // DB VERIFICATION: confirm role created
        if (TestDatabase.isAvailable() && roleId != null) {
            var dbRole = TestDatabase.getInstance().findRoleById(roleId);
            assertNotNull(dbRole, "Role should exist in DB after creation");
        }

        if (roleId != null) {
            // Cleanup: delete
            var deleteResponse = api.panelDelete("/v1/panel/roles/" + roleId);
            JsonHelper.assertStatus(deleteResponse, 200);

            // DB VERIFICATION: confirm role deleted
            if (TestDatabase.isAvailable()) {
                var dbRole = TestDatabase.getInstance().findRoleById(roleId);
                assertNull(dbRole, "Role should not exist in DB after deletion");
            }
        }
    }

    @Test
    void updateRole() throws Exception {
        // Create a role to update
        var createResponse = api.panelPost("/v1/panel/roles", Map.of(
            "name", "API Test Update " + System.currentTimeMillis(),
            "description", "Will be updated",
            "permissions", List.of()
        ));
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            return;
        }

        var json = JsonHelper.parseObject(createResponse.body());
        String roleId = json.has("role") ?
                        json.getAsJsonObject("role").get("id").getAsString() :
                        json.has("id") ? json.get("id").getAsString() : null;
        if (roleId == null) {
            return;
        }

        var updateResponse = api.panelPut("/v1/panel/roles/" + roleId, Map.of(
            "name", "API Test Updated " + System.currentTimeMillis(),
            "description", "Updated by test",
            "permissions", List.of()
        ));
        JsonHelper.assertStatus(updateResponse, 200);

        // Cleanup
        api.panelDelete("/v1/panel/roles/" + roleId);
    }

    @Test
    void reorderRoles() throws Exception {
        var listResponse = api.panelGet("/v1/panel/roles");
        var roles = JsonHelper.parseObject(listResponse.body()).getAsJsonArray("roles");
        if (roles.size() < 2) {
            return;
        }

        // Build roleOrder with id and order pairs
        List<Map<String, Object>> roleOrder = new java.util.ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            String id = roles.get(i).getAsJsonObject().get("id").getAsString();
            roleOrder.add(Map.of("id", id, "order", i));
        }

        var response = api.panelPost("/v1/panel/roles/reorder", Map.of("roleOrder", roleOrder));
        JsonHelper.assertStatus(response, 200);
    }
}

