package gg.modl.backend.infrastructure.cors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.infrastructure.config.ModlCorsProperties;
import gg.modl.backend.server.ServerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class DynamicCorsConfigurationSourceTest {
    private static final String REPLAY_LITE_PATH = "/v1/public/replay-lite/replays/75f4b741-67df-414c-957b-a8a08222fc30";

    @Test
    void replayLitePathsOnlyAllowConfiguredReplayLiteOrigins() {
        ServerService serverService = Mockito.mock(ServerService.class);
        DynamicCorsConfigurationSource source = source(serverService);

        assertNotNull(source.getCorsConfiguration(request(REPLAY_LITE_PATH, "https://replays.modl.gg")));
        assertNull(source.getCorsConfiguration(request(REPLAY_LITE_PATH, "https://modl.gg")));
        assertNull(source.getCorsConfiguration(request(REPLAY_LITE_PATH, "https://customer.modl.gg")));
        assertNull(source.getCorsConfiguration(request(REPLAY_LITE_PATH, "https://customer.example.com")));
        verifyNoInteractions(serverService);
    }

    @Test
    void nonReplayLitePathsKeepSystemAndAppDomainOrigins() {
        DynamicCorsConfigurationSource source = source(Mockito.mock(ServerService.class));

        assertNotNull(source.getCorsConfiguration(request("/v1/public/punishments/search", "https://modl.gg")));
        assertNotNull(source.getCorsConfiguration(request("/v1/public/punishments/search", "https://customer.modl.gg")));
    }

    private DynamicCorsConfigurationSource source(ServerService serverService) {
        ModlCorsProperties properties = new ModlCorsProperties();
        properties.setSystemOrigins("https://modl.gg,https://admin.modl.gg");
        properties.setAppDomains("modl.gg");
        properties.setReplayLiteOrigins("https://replays.modl.gg,http://localhost:5173");

        DynamicCorsConfigurationSource source = new DynamicCorsConfigurationSource(serverService, properties);
        source.initParsedOrigins();
        return source;
    }

    private MockHttpServletRequest request(String path, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Origin", origin);
        return request;
    }
}
