package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.StagingCredentials;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PublicPlayerApiTest {

    static ApiClient api;

    private static final String TEST_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPublicApiAvailable(), StagingCredentials.publicApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void getAvatarProxy() throws Exception {
        var response = api.publicGet("/v1/public/players/avatar/" + TEST_UUID + "?size=32&overlay=true");
        int status = response.statusCode();
        // 200 = image returned, 302 = redirect to avatar source
        assertTrue(status == 200 || status == 302, "Expected 200 or 302 but got " + status);
    }
}

