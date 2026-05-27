package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import gg.modl.backend.support.TestDataProvider;
import gg.modl.backend.support.TestDatabase;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PanelTicketApiTest {

    static ApiClient api;

    private static String testUuid;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();

        testUuid = TestDataProvider.getPlayers().get(0).uuid();
    }

    @Test
    void searchTickets() throws Exception {
        var response = api.panelGet("/v1/panel/tickets?page=1&limit=5");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getTicketCounts() throws Exception {
        var response = api.panelGet("/v1/panel/tickets/counts");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void createAndCleanupTicket() throws Exception {
        var createResponse = api.panelPost("/v1/panel/tickets", Map.of(
            "type", "bug_report",
            "subject", "Panel API Test Ticket",
            "description", "Created by automated test",
            "creatorName", "TestBot"
        ));
        int createStatus = createResponse.statusCode();
        assertTrue(createStatus == 200 || createStatus == 201, "Expected 200 or 201 but got " + createStatus);

        var json = JsonHelper.parseObject(createResponse.body());
        String ticketId = null;
        if (json.has("id")) {
            ticketId = json.get("id").getAsString();
        } else if (json.has("_id")) {
            ticketId = json.get("_id").getAsString();
        }

        // DB VERIFICATION: confirm ticket created
        if (TestDatabase.isAvailable() && ticketId != null) {
            var dbTicket = TestDatabase.getInstance().findTicketById(ticketId);
            assertNotNull(dbTicket, "Ticket should exist in DB after creation");
        }

        if (ticketId != null) {
            // Cleanup: close ticket
            api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
        }
    }

    @Test
    void getTicketById() throws Exception {
        // Create
        var createResponse = api.panelPost("/v1/panel/tickets", Map.of(
            "type", "bug_report",
            "subject", "Panel API Test - get by id"
        ));
        var json = JsonHelper.parseObject(createResponse.body());
        String ticketId = json.has("id") ? json.get("id").getAsString() :
                          json.has("_id") ? json.get("_id").getAsString() : null;
        if (ticketId == null) {
            return;
        }

        var response = api.panelGet("/v1/panel/tickets/" + ticketId);
        JsonHelper.assertStatus(response, 200);

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void updateTicket() throws Exception {
        var createResponse = api.panelPost("/v1/panel/tickets", Map.of(
            "type", "bug_report",
            "subject", "Panel API Test - update"
        ));
        var json = JsonHelper.parseObject(createResponse.body());
        String ticketId = json.has("id") ? json.get("id").getAsString() :
                          json.has("_id") ? json.get("_id").getAsString() : null;
        if (ticketId == null) {
            return;
        }

        var response = api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of(
            "status", "in_progress"
        ));
        JsonHelper.assertStatus(response, 200);

        // DB VERIFICATION: confirm status changed
        if (TestDatabase.isAvailable()) {
            var dbTicket = TestDatabase.getInstance().findTicketById(ticketId);
            assertNotNull(dbTicket, "Ticket should exist in DB");
            assertEquals("in_progress", dbTicket.getString("status"));
        }

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void addNoteToTicket() throws Exception {
        var createResponse = api.panelPost("/v1/panel/tickets", Map.of(
            "type", "bug_report",
            "subject", "Panel API Test - add note"
        ));
        var json = JsonHelper.parseObject(createResponse.body());
        String ticketId = json.has("id") ? json.get("id").getAsString() :
                          json.has("_id") ? json.get("_id").getAsString() : null;
        if (ticketId == null) {
            return;
        }

        var response = api.panelPost("/v1/panel/tickets/" + ticketId + "/notes", Map.of(
            "text", "Panel API test note",
            "issuerName", "TestBot"
        ));
        int noteStatus = response.statusCode();
        assertTrue(noteStatus == 200 || noteStatus == 201, "Expected 200 or 201 but got " + noteStatus);

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void addReplyToTicket() throws Exception {
        var createResponse = api.panelPost("/v1/panel/tickets", Map.of(
            "type", "bug_report",
            "subject", "Panel API Test - add reply"
        ));
        var json = JsonHelper.parseObject(createResponse.body());
        String ticketId = json.has("id") ? json.get("id").getAsString() :
                          json.has("_id") ? json.get("_id").getAsString() : null;
        if (ticketId == null) {
            return;
        }

        var response = api.panelPost("/v1/panel/tickets/" + ticketId + "/replies", Map.of(
            "name", "TestBot",
            "content", "Automated test reply",
            "staff", true
        ));
        int replyStatus = response.statusCode();
        assertTrue(replyStatus == 200 || replyStatus == 201, "Expected 200 or 201 but got " + replyStatus);

        // DB VERIFICATION: confirm reply added
        if (TestDatabase.isAvailable()) {
            var dbTicket = TestDatabase.getInstance().findTicketById(ticketId);
            assertNotNull(dbTicket, "Ticket should exist in DB");
            var replies = dbTicket.getList("replies", Document.class);
            assertNotNull(replies, "Replies list should exist");
            assertTrue(replies.stream().anyMatch(r ->
                    "Automated test reply".equals(r.getString("content"))),
                "Should contain the test reply");
        }

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void addTag() throws Exception {
        var createResponse = api.panelPost("/v1/panel/tickets", Map.of(
            "type", "bug_report",
            "subject", "Panel API Test - tags"
        ));
        var json = JsonHelper.parseObject(createResponse.body());
        String ticketId = json.has("id") ? json.get("id").getAsString() :
                          json.has("_id") ? json.get("_id").getAsString() : null;
        if (ticketId == null) {
            return;
        }

        var response = api.panelPost("/v1/panel/tickets/" + ticketId + "/tags", Map.of(
            "tag", "api-test"
        ));
        JsonHelper.assertStatus(response, 200);

        // DB VERIFICATION: confirm tag added
        if (TestDatabase.isAvailable()) {
            var dbTicket = TestDatabase.getInstance().findTicketById(ticketId);
            assertNotNull(dbTicket, "Ticket should exist in DB");
            var tags = dbTicket.getList("tags", String.class);
            assertNotNull(tags, "Tags list should exist");
            assertTrue(tags.contains("api-test"), "Should contain the api-test tag");
        }

        // Remove tag
        api.panelDelete("/v1/panel/tickets/" + ticketId + "/tags/api-test");

        // DB VERIFICATION: confirm tag removed
        if (TestDatabase.isAvailable()) {
            var dbTicket = TestDatabase.getInstance().findTicketById(ticketId);
            assertNotNull(dbTicket, "Ticket should exist in DB");
            var tags = dbTicket.getList("tags", String.class);
            assertTrue(tags == null || !tags.contains("api-test"), "Tag should be removed");
        }

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void removeTag() throws Exception {
        var createResponse = api.panelPost("/v1/panel/tickets", Map.of(
            "type", "bug_report",
            "subject", "Panel API Test - remove tag"
        ));
        var json = JsonHelper.parseObject(createResponse.body());
        String ticketId = json.has("id") ? json.get("id").getAsString() :
                          json.has("_id") ? json.get("_id").getAsString() : null;
        if (ticketId == null) {
            return;
        }

        // Add then remove
        api.panelPost("/v1/panel/tickets/" + ticketId + "/tags", Map.of("tag", "temp-tag"));
        var response = api.panelDelete("/v1/panel/tickets/" + ticketId + "/tags/temp-tag");
        JsonHelper.assertStatus(response, 200);

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void getTicketsByPlayer() throws Exception {
        var response = api.panelGet("/v1/panel/tickets/player/" + testUuid);
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getTicketsByTag() throws Exception {
        var response = api.panelGet("/v1/panel/tickets/tag/api-test");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void bulkUpdate() throws Exception {
        // Create two tickets
        var r1 = api.panelPost("/v1/panel/tickets", Map.of("type", "bug_report", "subject", "Bulk test 1"));
        var r2 = api.panelPost("/v1/panel/tickets", Map.of("type", "bug_report", "subject", "Bulk test 2"));
        var j1 = JsonHelper.parseObject(r1.body());
        var j2 = JsonHelper.parseObject(r2.body());
        String id1 = j1.has("id") ? j1.get("id").getAsString() : j1.has("_id") ? j1.get("_id").getAsString() : null;
        String id2 = j2.has("id") ? j2.get("id").getAsString() : j2.has("_id") ? j2.get("_id").getAsString() : null;
        if (id1 == null || id2 == null) {
            return;
        }

        var response = api.panelPost("/v1/panel/tickets/bulk", Map.of(
            "ticketIds", List.of(id1, id2)
        ));
        JsonHelper.assertStatus(response, 200);

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + id1, Map.of("status", "closed"));
        api.panelPatch("/v1/panel/tickets/" + id2, Map.of("status", "closed"));
    }
}

