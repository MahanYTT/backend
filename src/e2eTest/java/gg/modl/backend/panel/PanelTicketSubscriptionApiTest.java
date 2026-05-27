package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PanelTicketSubscriptionApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void listSubscriptions() throws Exception {
        var response = api.panelGet("/v1/panel/ticket-subscriptions");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getUpdates() throws Exception {
        var response = api.panelGet("/v1/panel/ticket-subscriptions/updates?limit=5");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void markUpdateAsRead() throws Exception {
        var response = api.panelPost("/v1/panel/ticket-subscriptions/updates/nonexistent-id/read", Map.of());
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getAssignedUpdates() throws Exception {
        var response = api.panelGet("/v1/panel/ticket-subscriptions/assigned-updates?limit=5");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void unsubscribe() throws Exception {
        var response = api.panelDelete("/v1/panel/ticket-subscriptions/nonexistent-ticket-id");
        assertEquals(404, response.statusCode(), "Expected 404 for nonexistent ticket");
    }
}

