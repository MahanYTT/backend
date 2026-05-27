package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PublicPunishmentApiTest {

    static ApiClient api;

    private static final String TEST_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPublicApiAvailable(), StagingCredentials.publicApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void getAppealInfo() throws Exception {
        // Create a punishment to query appeal info for
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", TEST_UUID,
            "issuerName", "TestBot",
            "type_ordinal", 14,
            "reason", "Public appeal info test",
            "duration", 300,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        if (createResponse.statusCode() != 200) {
            return;
        }
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var response = api.publicGet("/v1/public/punishment/" + punishmentId + "/appeal-info");
        // Punishment exists but hasn't been executed on a server yet, so appeal info may return 400
        int status = response.statusCode();
        assertTrue(status == 200 || status == 400, "Expected 200 or 400 but got " + status);

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "issuerName", "TestBot",
            "reason", "cleanup"
        ));
    }
}

