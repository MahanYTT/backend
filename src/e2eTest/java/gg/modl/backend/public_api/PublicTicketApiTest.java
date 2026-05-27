package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import gg.modl.backend.support.TestDataProvider;
import gg.modl.backend.support.TestDatabase;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PublicTicketApiTest {

    static ApiClient api;

    private static String testUuid;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPublicApiAvailable(), StagingCredentials.publicApiUnavailableReason());
        api = new ApiClient();

        testUuid = TestDataProvider.getPlayers().get(0).uuid();
    }

    @Test
    void createAndGetTicket() throws Exception {
        var createResponse = api.publicPost("/v1/public/tickets", Map.of(
            "type", "bug_report",
            "subject", "Public API Test Ticket",
            "description", "Created by automated public API test",
            "creatorName", "PublicUser",
            "creatorUuid", testUuid
        ));
        int status = createResponse.statusCode();
        assertTrue(status == 200 || status == 201 || status == 429, "Expected 200, 201, or 429 but got " + status);
        if (status == 429) {
            return;
        }

        var json = JsonHelper.parseObject(createResponse.body());
        assertTrue(json.has("ticketId"));
        String ticketId = json.get("ticketId").getAsString();

        // DB VERIFICATION: confirm ticket created
        if (TestDatabase.isAvailable()) {
            var dbTicket = TestDatabase.getInstance().findTicketById(ticketId);
            assertNotNull(dbTicket, "Ticket should exist in DB after public creation");
            assertEquals("bug", dbTicket.getString("type"));
        }

        // Get ticket
        var getResponse = api.publicGet("/v1/public/tickets/" + ticketId);
        JsonHelper.assertStatus(getResponse, 200);

        // Cleanup via panel
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void createUnfinishedTicket() throws Exception {
        var response = api.publicPost("/v1/public/tickets/unfinished", Map.of(
            "type", "bug_report",
            "creatorName", "PublicUser",
            "creatorUuid", testUuid
        ));
        int status = response.statusCode();
        assertTrue(status == 200 || status == 201 || status == 429, "Expected 200, 201, or 429 but got " + status);

        if (status == 200 || status == 201) {
            var json = JsonHelper.parseObject(response.body());
            if (json.has("ticketId")) {
                api.panelPatch("/v1/panel/tickets/" + json.get("ticketId").getAsString(),
                    Map.of("status", "closed"));
            }
        }
    }

    @Test
    void getTicketStatus() throws Exception {
        // Create a ticket first
        var createResponse = api.publicPost("/v1/public/tickets", Map.of(
            "type", "bug_report",
            "subject", "Public API Test - status",
            "creatorUuid", testUuid
        ));
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            return;
        }
        String ticketId = JsonHelper.parseObject(createResponse.body()).get("ticketId").getAsString();

        var response = api.publicGet("/v1/public/tickets/" + ticketId + "/status");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("status"));

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void addReplyToTicket() throws Exception {
        var createResponse = api.publicPost("/v1/public/tickets", Map.of(
            "type", "bug_report",
            "subject", "Public API Test - reply",
            "creatorUuid", testUuid
        ));
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            return;
        }
        String ticketId = JsonHelper.parseObject(createResponse.body()).get("ticketId").getAsString();

        var response = api.publicPost("/v1/public/tickets/" + ticketId + "/replies", Map.of(
            "name", "PublicUser",
            "content", "Public test reply"
        ));
        int status = response.statusCode();
        assertTrue(status == 200 || status == 201 || status == 429, "Expected 200, 201, or 429 but got " + status);

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void submitTicketForm() throws Exception {
        var createResponse = api.publicPost("/v1/public/tickets/unfinished", Map.of(
            "type", "bug_report",
            "creatorUuid", testUuid
        ));
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            return;
        }
        String ticketId = JsonHelper.parseObject(createResponse.body()).get("ticketId").getAsString();

        var response = api.publicPost("/v1/public/tickets/" + ticketId + "/submit", Map.of(
            "subject", "Submitted form test",
            "description", "Test submission"
        ));
        int submitStatus = response.statusCode();
        assertTrue(submitStatus == 200 || submitStatus == 429, "Expected 200 or 429 but got " + submitStatus);

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void requestVerification() throws Exception {
        var createResponse = api.publicPost("/v1/public/tickets", Map.of(
            "type", "bug_report",
            "subject", "Public API Test - verify",
            "creatorUuid", testUuid,
            "emailAuthEnabled", true,
            "creatorEmail", "test@example.com"
        ));
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            return;
        }
        String ticketId = JsonHelper.parseObject(createResponse.body()).get("ticketId").getAsString();

        var response = api.publicPost("/v1/public/tickets/" + ticketId + "/request-verification", Map.of());
        int verifyStatus = response.statusCode();
        assertTrue(verifyStatus == 200 || verifyStatus == 429, "Expected 200 or 429 but got " + verifyStatus);

        // Cleanup
        api.panelPatch("/v1/panel/tickets/" + ticketId, Map.of("status", "closed"));
    }

    @Test
    void verifyCode() throws Exception {
        var response = api.publicPost("/v1/public/tickets/nonexistent-id/verify", Map.of(
            "code", "000000"
        ));
        assertEquals(403, response.statusCode());
    }

    @Test
    void createTicketRejectsInvalidEmail() throws Exception {
        var response = api.publicPost("/v1/public/tickets", Map.of(
            "type", "bug_report",
            "subject", "Public API Test - invalid email",
            "creatorName", "PublicUser",
            "creatorEmail", "asfas"
        ));

        int status = response.statusCode();
        assertTrue(status == 400 || status == 429, "Expected 400 or 429 but got " + status);
    }

    @Test
    void submitTicketFormRejectsInvalidEmail() throws Exception {
        var createResponse = api.publicPost("/v1/public/tickets/unfinished", Map.of(
            "type", "bug_report",
            "creatorUuid", testUuid
        ));
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            return;
        }
        String ticketId = JsonHelper.parseObject(createResponse.body()).get("ticketId").getAsString();

        var response = api.publicPost("/v1/public/tickets/" + ticketId + "/submit", Map.of(
            "subject", "Invalid email submit test",
            "creatorEmail", "asfas",
            "formData", Map.of("description", "Test submission")
        ));

        int status = response.statusCode();
        assertTrue(status == 400 || status == 429, "Expected 400 or 429 but got " + status);
    }
}

