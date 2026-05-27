package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.dashboard.controller.v3.MinecraftDashboardV3Controller;
import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.backend.dashboard.service.DashboardService;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.filter.ApiKeyFilter;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.service.ApiKeySettingsService;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.MinecraftDashboardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftV3ApiKeyFilterMockMvcTest {
    private static final String VALID_API_KEY = "valid-api-key";
    private static final String INVALID_API_KEY = "invalid-api-key";

    @Mock private ApiKeySettingsService apiKeySettingsService;
    @Mock private DashboardService dashboardService;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        ApiKeyFilter apiKeyFilter = new ApiKeyFilter(apiKeySettingsService, new ProtobufErrorResponseWriter());
        mockMvc = MockMvcBuilders.standaloneSetup(new MinecraftDashboardV3Controller(dashboardService))
            .addFilters(apiKeyFilter)
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .build();
    }

    @Test
    void v3MinecraftRouteWithoutApiKeyReturnsBinaryUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(401, error.getStatusCode());
        assertEquals("UNAUTHENTICATED", error.getCode());
        verifyNoInteractions(apiKeySettingsService, dashboardService);
    }

    @Test
    void v3MinecraftRouteWithInvalidApiKeyReturnsBinaryUnauthorized() throws Exception {
        when(apiKeySettingsService.findServerByApiKey(INVALID_API_KEY)).thenReturn(null);

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .header(RequestHeader.API_KEY, INVALID_API_KEY)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(401, error.getStatusCode());
        assertEquals("UNAUTHENTICATED", error.getCode());
        verify(apiKeySettingsService).findServerByApiKey(INVALID_API_KEY);
        verifyNoInteractions(dashboardService);
    }

    @Test
    void v3MinecraftRouteWithValidApiKeyUsesAuthenticatedServer() throws Exception {
        MinecraftDashboardStatsResponse stats = new MinecraftDashboardStatsResponse(
            11,
            12,
            13,
            14,
            15,
            16,
            31,
            99
        );
        when(apiKeySettingsService.findServerByApiKey(VALID_API_KEY)).thenReturn(server);
        when(dashboardService.getMinecraftStats(server)).thenReturn(stats);

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .header(RequestHeader.API_KEY, VALID_API_KEY)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftDashboardResponse response = MinecraftDashboardResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(99, response.getStats().getTotalPlayers());
        verify(apiKeySettingsService).findServerByApiKey(VALID_API_KEY);
        verify(dashboardService).getMinecraftStats(same(server));
    }
}
