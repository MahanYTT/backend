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

class PanelHomepageCardApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void listCards() throws Exception {
        var response = api.panelGet("/v1/panel/homepage-cards");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void createAndDeleteCard() throws Exception {
        var createResponse = api.panelPost("/v1/panel/homepage-cards", Map.of(
            "title", "API Test Card " + System.currentTimeMillis(),
            "description", "Created by automated test",
            "actionType", "url",
            "actionUrl", "https://example.com",
            "isEnabled", false
        ));
        int status = createResponse.statusCode();
        assertTrue(status == 200 || status == 201, "Expected 200 or 201 but got " + status);

        var json = JsonHelper.parseObject(createResponse.body());
        String cardId = json.has("id") ? json.get("id").getAsString() :
                        json.has("_id") ? json.get("_id").getAsString() : null;
        if (cardId == null) {
            return;
        }

        // DB VERIFICATION: confirm card created
        if (TestDatabase.isAvailable()) {
            var dbCard = TestDatabase.getInstance().findHomepageCardById(cardId);
            assertNotNull(dbCard, "Homepage card should exist in DB after creation");
        }

        // Cleanup
        var deleteResponse = api.panelDelete("/v1/panel/homepage-cards/" + cardId);
        JsonHelper.assertStatus(deleteResponse, 200);

        // DB VERIFICATION: confirm card deleted
        if (TestDatabase.isAvailable()) {
            var dbCard = TestDatabase.getInstance().findHomepageCardById(cardId);
            assertNull(dbCard, "Homepage card should not exist in DB after deletion");
        }
    }

    @Test
    void updateCard() throws Exception {
        var createResponse = api.panelPost("/v1/panel/homepage-cards", Map.of(
            "title", "API Test Update Card",
            "isEnabled", false
        ));
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            return;
        }
        var json = JsonHelper.parseObject(createResponse.body());
        String cardId = json.has("id") ? json.get("id").getAsString() :
                        json.has("_id") ? json.get("_id").getAsString() : null;
        if (cardId == null) {
            return;
        }

        var updateResponse = api.panelPut("/v1/panel/homepage-cards/" + cardId, Map.of(
            "title", "API Test Card Updated",
            "description", "Updated by test"
        ));
        JsonHelper.assertStatus(updateResponse, 200);

        // Cleanup
        api.panelDelete("/v1/panel/homepage-cards/" + cardId);
    }

    @Test
    void reorderCards() throws Exception {
        var listResponse = api.panelGet("/v1/panel/homepage-cards");
        var arr = JsonHelper.parseArray(listResponse.body());
        if (arr.size() < 2) {
            return;
        }

        List<String> ids = new java.util.ArrayList<>();
        arr.forEach(c -> {
            var obj = c.getAsJsonObject();
            ids.add(obj.has("id") ? obj.get("id").getAsString() : obj.get("_id").getAsString());
        });

        var response = api.panelPut("/v1/panel/homepage-cards/reorder", Map.of("ids", ids));
        JsonHelper.assertStatus(response, 200);
    }
}

