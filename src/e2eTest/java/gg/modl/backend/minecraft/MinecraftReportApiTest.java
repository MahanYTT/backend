package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftReportApiTest {

    static ApiClient api;

    private static final String TEST_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();
    }

    @Test
    void listOpenReports() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/reports?status=open&limit=10");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("reports"));
    }

    @Test
    void listPlayerReports() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/reports/player/" + TEST_UUID + "?status=all&limit=5");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("reports"));
    }

    @Test
    void dismissReport() throws Exception {
        var listResponse = api.minecraftGet("/v1/minecraft/reports?status=open&limit=1");
        var json = JsonHelper.parseObject(listResponse.body());
        var reports = json.getAsJsonArray("reports");
        if (reports.isEmpty()) {
            return;
        }

        String reportId = reports.get(0).getAsJsonObject().get("id").getAsString();

        var response = api.minecraftPost("/v1/minecraft/reports/" + reportId + "/dismiss", Map.of(
            "dismissedBy", "TestBot",
            "reason", "API test - dismissed for testing"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void assignReport() throws Exception {
        var listResponse = api.minecraftGet("/v1/minecraft/reports?status=open&limit=1");
        var json = JsonHelper.parseObject(listResponse.body());
        var reports = json.getAsJsonArray("reports");
        if (reports.isEmpty()) {
            return;
        }

        String reportId = reports.get(0).getAsJsonObject().get("id").getAsString();

        var response = api.minecraftPost("/v1/minecraft/reports/" + reportId + "/assign", Map.of(
            "assignee", "TestBot"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void resolveReport() throws Exception {
        var listResponse = api.minecraftGet("/v1/minecraft/reports?status=open&limit=1");
        var json = JsonHelper.parseObject(listResponse.body());
        var reports = json.getAsJsonArray("reports");
        if (reports.isEmpty()) {
            return;
        }

        String reportId = reports.get(0).getAsJsonObject().get("id").getAsString();

        var response = api.minecraftPost("/v1/minecraft/reports/" + reportId + "/resolve", Map.of(
            "resolvedBy", "TestBot",
            "resolution", "API test - resolved for testing",
            "punishmentId", "test-punishment-id"
        ));
        JsonHelper.assertStatus(response, 200);
    }
}
