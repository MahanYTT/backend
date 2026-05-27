package gg.modl.backend.replaylite.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.filter.ApiKeyFilter;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.replaylite.dto.ReplayLiteUploadInitResponse;
import gg.modl.backend.replaylite.service.ReplayLiteService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.service.ApiKeySettingsService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ReplayLiteApiKeyFilterMockMvcTest {
    private static final String VALID_API_KEY = "valid-api-key";
    private static final String INVALID_API_KEY = "invalid-api-key";

    @Mock private ApiKeySettingsService apiKeySettingsService;
    @Mock private ReplayLiteService replayLiteService;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);
        server.setId("507f1f77bcf86cd799439011");

        ApiKeyFilter apiKeyFilter = new ApiKeyFilter(apiKeySettingsService, new ProtobufErrorResponseWriter());
        mockMvc = MockMvcBuilders.standaloneSetup(new ReplayLiteController(replayLiteService))
            .addFilters(apiKeyFilter)
            .build();
    }

    @Test
    void replayLiteUploadWithoutApiKeyReturnsUnauthorized() throws Exception {
        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .content(uploadBody()))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(apiKeySettingsService, replayLiteService);
    }

    @Test
    void replayLiteUploadWithInvalidApiKeyReturnsUnauthorized() throws Exception {
        when(apiKeySettingsService.findServerByApiKey(INVALID_API_KEY)).thenReturn(null);

        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/upload")
                .header(RequestHeader.API_KEY, INVALID_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(uploadBody()))
            .andExpect(status().isUnauthorized());

        verify(apiKeySettingsService).findServerByApiKey(INVALID_API_KEY);
        verifyNoInteractions(replayLiteService);
    }

    @Test
    void replayLiteUploadWithValidApiKeyUsesAuthenticatedServer() throws Exception {
        when(apiKeySettingsService.findServerByApiKey(VALID_API_KEY)).thenReturn(server);
        when(replayLiteService.initUpload(same(server), any(), any()))
            .thenReturn(new ReplayLiteUploadInitResponse(
                "75f4b741-67df-414c-957b-a8a08222fc30",
                "https://uploads.example/replay",
                "PUT",
                Map.of("Content-Type", "application/octet-stream"),
                Instant.parse("2026-05-15T12:15:00Z")
            ));

        mockMvc.perform(post(RESTMappingV1.REPLAY_LITE_REPLAYS + "/upload")
                .header(RequestHeader.API_KEY, VALID_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(uploadBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replayId").value("75f4b741-67df-414c-957b-a8a08222fc30"));

        verify(apiKeySettingsService).findServerByApiKey(VALID_API_KEY);
        verify(replayLiteService).initUpload(same(server), any(), any());
    }

    private String uploadBody() {
        return """
            {
              "pluginServerUuid": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "requestedSize": 2048,
              "mcVersion": "1.21.4"
            }
            """;
    }
}
