package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.protobuf.Struct;
import gg.modl.backend.ai.service.AITicketAnalysisService;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.ticket.controller.v3.MinecraftTicketsV3Controller;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.service.MinecraftTicketService;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.ClaimTicketResponse;
import gg.modl.proto.modl.v1.MinecraftClaimTicketRequest;
import gg.modl.proto.modl.v1.MinecraftCreateTicketRequest;
import gg.modl.proto.modl.v1.MinecraftCreateTicketResponse;
import gg.modl.proto.modl.v1.MinecraftTicketDetailResponse;
import gg.modl.proto.modl.v1.MinecraftTicketsByIdsRequest;
import gg.modl.proto.modl.v1.TicketsResponse;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class MinecraftTicketV3ControllerTest {
    private static final String PLAYER_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String REPORTED_PLAYER_UUID = "22222222-3333-4444-5555-666666666666";

    @Mock private MinecraftTicketService ticketService;
    @Mock private AITicketAnalysisService aiTicketAnalysisService;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        mockMvc = MockMvcBuilders.standaloneSetup(new MinecraftTicketsV3Controller(ticketService, aiTicketAnalysisService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3CreateTicketMapsRequestCreatesTicketAndStartsChatAnalysis() throws Exception {
        Ticket ticket = ticket("TICKET-CREATE");
        when(ticketService.createMinecraftTicket(
            same(server),
            argThat(request -> PLAYER_UUID.equals(request.creatorUuid())
                && "PlayerOne".equals(request.creatorName())
                && "chat".equals(request.type())
                && "Chat report".equals(request.subject())
                && "first message".equals(request.description())
                && REPORTED_PLAYER_UUID.equals(request.reportedPlayerUuid())
                && "ReportedPlayer".equals(request.reportedPlayerName())
                && List.of("chat line").equals(request.chatMessages())
                && List.of("urgent").equals(request.tags())
                && "high".equals(request.priority())
                && "lobby".equals(request.createdServer())
                && "https://cdn.example/replay.modl".equals(request.replayUrl()))
        )).thenReturn(ticket);

        MinecraftCreateTicketRequest request = MinecraftCreateTicketRequest.newBuilder()
            .setCreatorUuid(PLAYER_UUID)
            .setCreatorName("PlayerOne")
            .setType("chat")
            .setSubject("Chat report")
            .setDescription("first message")
            .setReportedPlayerUuid(REPORTED_PLAYER_UUID)
            .setReportedPlayerName("ReportedPlayer")
            .addChatMessages("chat line")
            .addTags("urgent")
            .setPriority("high")
            .setCreatedServer("lobby")
            .setReplayUrl("https://cdn.example/replay.modl")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftCreateTicketResponse response = MinecraftCreateTicketResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("TICKET-CREATE", response.getTicketId());
        assertEquals("Ticket created successfully", response.getMessage());
        verify(aiTicketAnalysisService).analyzeTicketAsync(same(server), eq("TICKET-CREATE"));
    }

    @Test
    void v3CreateUnfinishedTicketUsesUnfinishedServiceAndDraftMessage() throws Exception {
        Ticket ticket = ticket("TICKET-DRAFT");
        when(ticketService.createUnfinishedMinecraftTicket(
            same(server),
            argThat(request -> PLAYER_UUID.equals(request.creatorUuid())
                && "support".equals(request.type())
                && request.chatMessages().isEmpty())
        )).thenReturn(ticket);

        MinecraftCreateTicketRequest request = MinecraftCreateTicketRequest.newBuilder()
            .setCreatorUuid(PLAYER_UUID)
            .setType("support")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/unfinished")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftCreateTicketResponse response = MinecraftCreateTicketResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("TICKET-DRAFT", response.getTicketId());
        assertEquals("Ticket draft created - complete the form on the panel", response.getMessage());
        verifyNoInteractions(aiTicketAnalysisService);
    }

    @Test
    void v3CreateTicketValidationFailureReturnsBinaryApiErrorBeforeServiceCall() throws Exception {
        MinecraftCreateTicketRequest request = MinecraftCreateTicketRequest.newBuilder()
            .setCreatorUuid("not-a-uuid")
            .setType("not-a-ticket-type")
            .setCreatorName("x")
            .addChatMessages("x".repeat(513))
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("creator_uuid")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("type")));
        verifyNoInteractions(ticketService, aiTicketAnalysisService);
    }

    @Test
    void v3CreateUnfinishedTicketValidationFailureReturnsBinaryApiErrorBeforeServiceCall() throws Exception {
        MinecraftCreateTicketRequest request = MinecraftCreateTicketRequest.newBuilder()
            .setCreatorUuid(PLAYER_UUID)
            .setType("not-a-ticket-type")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/unfinished")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("type")));
        verifyNoInteractions(ticketService, aiTicketAnalysisService);
    }

    @Test
    void v3ClaimTicketReturnsSuccessResponseWithSubject() throws Exception {
        Ticket ticket = ticket("TICKET-CLAIM");
        when(ticketService.claimMinecraftTicket(
            same(server),
            eq("TICKET-CLAIM"),
            argThat(request -> PLAYER_UUID.equals(request.playerUuid()) && "PlayerOne".equals(request.playerName()))
        )).thenReturn(new MinecraftTicketService.MinecraftTicketClaimResult(
            MinecraftTicketService.MinecraftTicketClaimStatus.SUCCESS,
            ticket
        ));

        MinecraftClaimTicketRequest request = MinecraftClaimTicketRequest.newBuilder()
            .setPlayerUuid(PLAYER_UUID)
            .setPlayerName("PlayerOne")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/TICKET-CLAIM/claim")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ClaimTicketResponse response = ClaimTicketResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("Ticket successfully linked to your account", response.getMessage());
        assertEquals("TICKET-CLAIM", response.getTicketId());
        assertEquals("Need help", response.getSubject());
    }

    @Test
    void v3ClaimTicketReturnsNotFoundResponse() throws Exception {
        when(ticketService.claimMinecraftTicket(
            same(server),
            eq("missing"),
            argThat(request -> PLAYER_UUID.equals(request.playerUuid()) && "PlayerOne".equals(request.playerName()))
        )).thenReturn(new MinecraftTicketService.MinecraftTicketClaimResult(
            MinecraftTicketService.MinecraftTicketClaimStatus.NOT_FOUND,
            null
        ));

        MinecraftClaimTicketRequest request = MinecraftClaimTicketRequest.newBuilder()
            .setPlayerUuid(PLAYER_UUID)
            .setPlayerName("PlayerOne")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/missing/claim")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ClaimTicketResponse response = ClaimTicketResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Ticket not found", response.getMessage());
    }

    @Test
    void v3ClaimTicketReturnsAlreadyLinkedConflictResponse() throws Exception {
        when(ticketService.claimMinecraftTicket(
            same(server),
            eq("TICKET-LINKED"),
            argThat(request -> PLAYER_UUID.equals(request.playerUuid()) && "PlayerOne".equals(request.playerName()))
        )).thenReturn(new MinecraftTicketService.MinecraftTicketClaimResult(
            MinecraftTicketService.MinecraftTicketClaimStatus.ALREADY_LINKED,
            ticket("TICKET-LINKED")
        ));

        MinecraftClaimTicketRequest request = MinecraftClaimTicketRequest.newBuilder()
            .setPlayerUuid(PLAYER_UUID)
            .setPlayerName("PlayerOne")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/TICKET-LINKED/claim")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ClaimTicketResponse response = ClaimTicketResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(409, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Ticket is already linked to a Minecraft account", response.getMessage());
    }

    @Test
    void v3ClaimTicketValidationFailureReturnsBinaryApiErrorBeforeServiceCall() throws Exception {
        MinecraftClaimTicketRequest request = MinecraftClaimTicketRequest.newBuilder()
            .setPlayerUuid("not-a-uuid")
            .setPlayerName("x")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/TICKET-CLAIM/claim")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("player_uuid")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("player_name")));
        verifyNoInteractions(ticketService, aiTicketAnalysisService);
    }

    @Test
    void v3TicketListUsesDefaultLimitAndMapsServiceListItems() throws Exception {
        Ticket ticket = ticket("TICKET-1");
        ticket.setReplies(List.of(staffReply("reply-1", "First staff reply", 1_700_000_020_000L)));
        when(ticketService.getMinecraftTickets(server, "open", "support", 50)).thenReturn(List.of(ticket));
        when(ticketService.toTicketListItem(ticket)).thenReturn(ticketListItem(ticket));

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/tickets")
                .param("status", "open")
                .param("type", "support")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        TicketsResponse response = TicketsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(1, response.getTicketsCount());
        assertEquals("TICKET-1", response.getTickets(0).getId());
        assertEquals("support", response.getTickets(0).getType());
        assertEquals("support", response.getTickets(0).getCategory());
        assertEquals("Need help", response.getTickets(0).getSubject());
        assertEquals("open", response.getTickets(0).getStatus());
        assertEquals("PlayerOne", response.getTickets(0).getPlayerName());
        assertEquals(PLAYER_UUID, response.getTickets(0).getPlayerUuid());
        assertEquals("high", response.getTickets(0).getPriority());
        assertEquals(List.of("staff-1"), response.getTickets(0).getAssignedToList());
        assertEquals(1_700_000_000_000L, response.getTickets(0).getCreatedAt());
        assertTrue(response.getTickets(0).hasUpdatedAt());
        assertEquals(1_700_000_010_000L, response.getTickets(0).getUpdatedAt());
        assertTrue(response.getTickets(0).getHasStaffResponse());
        assertTrue(response.getTickets(0).getLocked());
        assertEquals(1, response.getTickets(0).getReplyCount());
        verify(ticketService).getMinecraftTickets(same(server), eq("open"), eq("support"), eq(50));
    }

    @Test
    void v3TicketListRejectsInvalidLimitBeforeServiceCall() throws Exception {
        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/tickets")
                .param("limit", "101")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        verifyNoInteractions(ticketService);
    }

    @Test
    void v3TicketDetailMapsRepliesChatMessagesAndReplayUrl() throws Exception {
        Ticket ticket = ticket("TICKET-2");
        ticket.setReplies(List.of(staffReply("reply-2", "Handled", 1_700_000_020_000L)));
        ticket.setChatMessages(List.of(new Ticket.ChatMessage("hello chat", new Date(1_700_000_030_000L))));
        ticket.setReplayUrl("https://cdn.example/replay.modl");
        when(ticketService.getMinecraftTicket(server, "TICKET-2")).thenReturn(Optional.of(ticket));
        when(ticketService.toTicketDetail(ticket)).thenReturn(ticketDetail(ticket));

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/TICKET-2")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftTicketDetailResponse response = MinecraftTicketDetailResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("TICKET-2", response.getTicket().getId());
        assertEquals("PlayerOne", response.getTicket().getPlayerName());
        assertEquals(1_700_000_000L, response.getTicket().getCreatedAt().getSeconds());
        assertTrue(response.getTicket().hasUpdatedAt());
        assertEquals(1_700_000_010L, response.getTicket().getUpdatedAt().getSeconds());
        assertEquals(1, response.getTicket().getRepliesCount());
        assertEquals("reply-2", response.getTicket().getReplies(0).getId());
        assertEquals("Handled", response.getTicket().getReplies(0).getContent());
        assertEquals("StaffOne", response.getTicket().getReplies(0).getAuthorName());
        assertEquals("staff-uuid", response.getTicket().getReplies(0).getAuthorId());
        assertTrue(response.getTicket().getReplies(0).getIsStaff());
        assertEquals(1_700_000_020L, response.getTicket().getReplies(0).getCreatedAt().getSeconds());
        assertEquals(1, response.getTicket().getChatMessagesCount());
        Struct chatMessage = response.getTicket().getChatMessages(0);
        assertEquals("hello chat", chatMessage.getFieldsOrThrow("content").getStringValue());
        assertEquals(Instant.ofEpochMilli(1_700_000_030_000L).toString(), chatMessage.getFieldsOrThrow("timestamp").getStringValue());
        assertTrue(response.getTicket().hasReplayUrl());
        assertEquals("https://cdn.example/replay.modl", response.getTicket().getReplayUrl());
    }

    @Test
    void v3TicketDetailNotFoundReturnsBinaryApiError() throws Exception {
        when(ticketService.getMinecraftTicket(server, "missing")).thenReturn(Optional.empty());

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/missing")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_FOUND", error.getCode());
        assertEquals("Ticket not found", error.getMessage());
    }

    @Test
    void v3PlayerTicketsMapsLegacySmallerItemsWithoutInventingMissingFields() throws Exception {
        Ticket ticket = ticket("TICKET-3");
        ticket.setPriority(null);
        ticket.setAssignedTo(List.of("staff-ignored"));
        when(ticketService.getMinecraftTicketsByCreator(server, PLAYER_UUID, 50)).thenReturn(List.of(ticket));

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/player/" + PLAYER_UUID)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        TicketsResponse response = TicketsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("TICKET-3", response.getTickets(0).getId());
        assertEquals("support", response.getTickets(0).getType());
        assertEquals("support", response.getTickets(0).getCategory());
        assertEquals("Need help", response.getTickets(0).getSubject());
        assertEquals("open", response.getTickets(0).getStatus());
        assertEquals(1_700_000_000_000L, response.getTickets(0).getCreatedAt());
        assertEquals("", response.getTickets(0).getPriority());
        assertEquals(0, response.getTickets(0).getAssignedToCount());
        assertFalse(response.getTickets(0).hasUpdatedAt());
        assertFalse(response.getTickets(0).hasFirstReplyContent());
        verify(ticketService).getMinecraftTicketsByCreator(same(server), eq(PLAYER_UUID), eq(50));
    }

    @Test
    void v3TicketsByIdsMapsRequestIdsAndLookupItems() throws Exception {
        Ticket ticket = ticket("TICKET-4");
        ticket.setReplies(List.of(playerReply("reply-4", "Initial content", 1_700_000_040_000L)));
        when(ticketService.getMinecraftTicketsByIds(server, List.of("TICKET-4", "TICKET-5"))).thenReturn(List.of(ticket));
        when(ticketService.toTicketLookupItem(ticket)).thenReturn(ticketLookupItem(ticket));

        MinecraftTicketsByIdsRequest request = MinecraftTicketsByIdsRequest.newBuilder()
            .addIds("TICKET-4")
            .addIds("TICKET-5")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/by-ids")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        TicketsResponse response = TicketsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(1, response.getTicketsCount());
        assertEquals("TICKET-4", response.getTickets(0).getId());
        assertTrue(response.getTickets(0).hasFirstReplyContent());
        assertEquals("Initial content", response.getTickets(0).getFirstReplyContent());
        verify(ticketService).getMinecraftTicketsByIds(same(server), eq(List.of("TICKET-4", "TICKET-5")));
    }

    @Test
    void v3TicketsByIdsAllowsEmptyIdsLikeLegacyAndReturnsEmptyList() throws Exception {
        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/by-ids")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(MinecraftTicketsByIdsRequest.newBuilder().build().toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        TicketsResponse response = TicketsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(0, response.getTicketsCount());
        verify(ticketService, never()).getMinecraftTicketsByIds(same(server), eq(List.of()));
    }

    @Test
    void v3TicketListRejectsJsonAcceptWithBinaryApiErrorBeforeServiceCall() throws Exception {
        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/tickets")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
        verifyNoInteractions(ticketService);
    }

    @Test
    void v3TicketsByIdsRejectsJsonContentTypeWithBinaryApiErrorBeforeServiceCall() throws Exception {
        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets/by-ids")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(ticketService);
    }

    private static Ticket ticket(String id) {
        return Ticket.builder()
            .id(id)
            .type(TicketCategory.SUPPORT)
            .subject("Need help")
            .status(TicketStatus.OPEN)
            .creatorName("PlayerOne")
            .creatorUuid(PLAYER_UUID)
            .priority(TicketPriority.HIGH)
            .assignedTo(List.of("staff-1"))
            .created(new Date(1_700_000_000_000L))
            .updatedAt(new Date(1_700_000_010_000L))
            .locked(true)
            .build();
    }

    private static TicketReply staffReply(String id, String content, long createdAt) {
        return TicketReply.builder()
            .id(id)
            .content(content)
            .name("StaffOne")
            .creatorIdentifier("staff-uuid")
            .staff(true)
            .created(new Date(createdAt))
            .build();
    }

    private static TicketReply playerReply(String id, String content, long createdAt) {
        return TicketReply.builder()
            .id(id)
            .content(content)
            .name("PlayerOne")
            .creatorIdentifier(PLAYER_UUID)
            .staff(false)
            .created(new Date(createdAt))
            .build();
    }

    private static Map<String, Object> ticketListItem(Ticket ticket) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", ticket.getId());
        item.put("type", "support");
        item.put("category", "support");
        item.put("subject", "Need help");
        item.put("status", "open");
        item.put("playerName", "PlayerOne");
        item.put("playerUuid", PLAYER_UUID);
        item.put("priority", "high");
        item.put("assignedTo", List.of("staff-1"));
        item.put("createdAt", new Date(1_700_000_000_000L));
        item.put("updatedAt", new Date(1_700_000_010_000L));
        item.put("hasStaffResponse", true);
        item.put("locked", true);
        item.put("replyCount", 1);
        return item;
    }

    private static Map<String, Object> ticketDetail(Ticket ticket) {
        Map<String, Object> item = ticketListItem(ticket);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", "reply-2");
        reply.put("content", "Handled");
        reply.put("authorName", "StaffOne");
        reply.put("authorId", "staff-uuid");
        reply.put("isStaff", true);
        reply.put("createdAt", new Date(1_700_000_020_000L));
        item.put("replies", List.of(reply));
        item.put("chatMessages", ticket.getChatMessages());
        item.put("replayUrl", "https://cdn.example/replay.modl");
        return item;
    }

    private static Map<String, Object> ticketLookupItem(Ticket ticket) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", ticket.getId());
        item.put("type", "support");
        item.put("category", "support");
        item.put("subject", "Need help");
        item.put("status", "open");
        item.put("playerName", "PlayerOne");
        item.put("playerUuid", PLAYER_UUID);
        item.put("createdAt", new Date(1_700_000_000_000L));
        item.put("firstReplyContent", "Initial content");
        return item;
    }
}
