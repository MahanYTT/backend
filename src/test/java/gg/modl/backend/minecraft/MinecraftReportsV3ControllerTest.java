package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.ticket.controller.v3.MinecraftReportsV3Controller;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.service.MinecraftTicketService;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.AssignReportRequest;
import gg.modl.proto.modl.v1.DismissReportRequest;
import gg.modl.proto.modl.v1.MinecraftReportOperationResponse;
import gg.modl.proto.modl.v1.ReportsResponse;
import gg.modl.proto.modl.v1.ResolveReportRequest;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftReportsV3ControllerTest {
    private static final String PLAYER_UUID = "11111111-2222-3333-4444-555555555555";

    @Mock private MinecraftTicketService ticketService;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        mockMvc = MockMvcBuilders.standaloneSetup(new MinecraftReportsV3Controller(ticketService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3ReportsListReturnsBinaryReportsResponse() throws Exception {
        when(ticketService.getMinecraftReports(server, "open", 25)).thenReturn(List.of(report()));

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/reports")
                .param("status", "open")
                .param("limit", "25")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ReportsResponse response = ReportsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(1, response.getReportsCount());
        assertEquals("report-1", response.getReports(0).getId());
        assertEquals("player", response.getReports(0).getType());
        assertEquals("Reporter", response.getReports(0).getReporterName());
        assertEquals(PLAYER_UUID, response.getReports(0).getReportedPlayerUuid());
        assertEquals("https://cdn.example/replay.modlreplay", response.getReports(0).getReplayUrl());
        assertEquals(1, response.getReports(0).getAssignedToCount());
        assertEquals(1, response.getReports(0).getChatMessagesCount());
        verify(ticketService).getMinecraftReports(same(server), eq("open"), eq(25));
    }

    @Test
    void v3PlayerReportsMapsStatusAndLimit() throws Exception {
        when(ticketService.getMinecraftReportsForPlayer(server, PLAYER_UUID, "all", 50)).thenReturn(List.of(report()));

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/reports/player/" + PLAYER_UUID)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ReportsResponse response = ReportsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(1, response.getReportsCount());
        verify(ticketService).getMinecraftReportsForPlayer(same(server), eq(PLAYER_UUID), eq("all"), eq(50));
    }

    @Test
    void v3DismissReportMapsRequestAndSuccessResponse() throws Exception {
        when(ticketService.dismissMinecraftReport(server, "report-1", "Mod", "Insufficient evidence"))
            .thenReturn(successResult());

        DismissReportRequest request = DismissReportRequest.newBuilder()
            .setDismissedBy("Mod")
            .setReason("Insufficient evidence")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/reports/report-1/dismiss")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftReportOperationResponse response = MinecraftReportOperationResponse.parseFrom(
            result.getResponse().getContentAsByteArray()
        );
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("Report dismissed", response.getMessage());
        verify(ticketService).dismissMinecraftReport(same(server), eq("report-1"), eq("Mod"), eq("Insufficient evidence"));
    }

    @Test
    void v3ResolveReportMapsRequestAndSuccessResponse() throws Exception {
        when(ticketService.resolveMinecraftReport(server, "report-1", "Mod", "Accepted", "punishment-1"))
            .thenReturn(successResult());

        ResolveReportRequest request = ResolveReportRequest.newBuilder()
            .setResolvedBy("Mod")
            .setResolution("Accepted")
            .setPunishmentId("punishment-1")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/reports/report-1/resolve")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftReportOperationResponse response = MinecraftReportOperationResponse.parseFrom(
            result.getResponse().getContentAsByteArray()
        );
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("Report resolved", response.getMessage());
        verify(ticketService).resolveMinecraftReport(same(server), eq("report-1"), eq("Mod"), eq("Accepted"), eq("punishment-1"));
    }

    @Test
    void v3AssignReportNotFoundReturnsBinaryOperationResponse() throws Exception {
        when(ticketService.assignMinecraftReport(server, "missing", "Mod"))
            .thenReturn(new MinecraftTicketService.ReportOperationResult(
                MinecraftTicketService.ReportOperationStatus.NOT_FOUND,
                null
            ));

        AssignReportRequest request = AssignReportRequest.newBuilder()
            .setAssignee("Mod")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/reports/missing/assign")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftReportOperationResponse response = MinecraftReportOperationResponse.parseFrom(
            result.getResponse().getContentAsByteArray()
        );
        assertEquals(404, response.getStatus());
        assertEquals("Report not found", response.getMessage());
        verify(ticketService).assignMinecraftReport(same(server), eq("missing"), eq("Mod"));
    }

    @Test
    void v3DismissReportValidationFailureReturnsBinaryApiErrorBeforeServiceCall() throws Exception {
        DismissReportRequest request = DismissReportRequest.newBuilder()
            .setReason("missing staff name")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/reports/report-1/dismiss")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        verifyNoInteractions(ticketService);
    }

    private static MinecraftTicketService.ReportOperationResult successResult() {
        return new MinecraftTicketService.ReportOperationResult(
            MinecraftTicketService.ReportOperationStatus.SUCCESS,
            null
        );
    }

    private static Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", "report-1");
        report.put("type", "player");
        report.put("reporterName", "Reporter");
        report.put("reporterUuid", "22222222-3333-4444-5555-666666666666");
        report.put("reportedPlayerUuid", PLAYER_UUID);
        report.put("reportedPlayerName", "BadPlayer");
        report.put("subject", "Rule break");
        report.put("content", "Evidence body");
        report.put("status", "open");
        report.put("priority", "normal");
        report.put("createdAt", 1_700_000_000_000L);
        report.put("assignedTo", List.of("Mod"));
        report.put("chatMessages", List.of(new Ticket.ChatMessage("hello", new Date(1_700_000_000_001L))));
        report.put("replayUrl", "https://cdn.example/replay.modlreplay");
        return report;
    }
}
