package gg.modl.backend.minecraft;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftNotificationApiTest {

    static ApiClient api;

    private static final String TEST_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isAvailable(), "Staging credentials not configured");
        api = new ApiClient();
    }

    @Test
    void acknowledgeNotifications() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/notifications/acknowledge", Map.of(
            "playerUuid", TEST_UUID,
            "notificationIds", List.of("nonexistent-id")
        ));
        JsonHelper.assertStatus(response, 200);
    }
}
