package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.dashboard.controller.v1.MinecraftDashboardController;
import gg.modl.backend.dashboard.controller.v3.MinecraftDashboardV3Controller;
import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.backend.dashboard.service.DashboardService;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.MinecraftDashboardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftDashboardV3ControllerTest {
    @Mock private DashboardService dashboardService;
    private MockMvc v3MockMvc;
    private MockMvc v1MockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        v3MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftDashboardV3Controller(dashboardService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();

        v1MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftDashboardController(dashboardService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3StatsReturnsBinaryResponseMappedFromDashboardService() throws Exception {
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
        when(dashboardService.getMinecraftStats(server)).thenReturn(stats);

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftDashboardResponse response = MinecraftDashboardResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(11, response.getStats().getUnresolvedReports());
        assertEquals(12, response.getStats().getUnresolvedTickets());
        assertEquals(13, response.getStats().getOnlineStaff());
        assertEquals(14, response.getStats().getOnlinePlayers());
        assertEquals(15, response.getStats().getActiveBans());
        assertEquals(16, response.getStats().getActiveMutes());
        assertEquals(31, response.getStats().getTotalActivePunishments());
        assertEquals(99, response.getStats().getTotalPlayers());
        verify(dashboardService).getMinecraftStats(same(server));
    }

    @Test
    void v3StatsRejectsJsonAcceptWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
    }

    @Test
    void v1StatsStillReturnsJsonEnvelope() throws Exception {
        MinecraftDashboardStatsResponse stats = new MinecraftDashboardStatsResponse(
            1,
            2,
            3,
            4,
            5,
            6,
            11,
            22
        );
        when(dashboardService.getMinecraftStats(server)).thenReturn(stats);

        MvcResult result = v1MockMvc.perform(get(RESTMappingV1.MINECRAFT_DASHBOARD + "/stats")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("status").asInt());
        assertEquals(22, json.get("stats").get("totalPlayers").asLong());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
    }
}
