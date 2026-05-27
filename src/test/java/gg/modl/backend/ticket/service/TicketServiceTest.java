package gg.modl.backend.ticket.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.realtime.dispatch.RealtimeEventDispatcher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.settings.service.QuickResponseSettingsService;
import gg.modl.backend.settings.service.TicketFormSettingsService;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import gg.modl.backend.ticket.dto.request.DismissReportRequest;
import gg.modl.backend.ticket.dto.request.MinecraftClaimTicketRequest;
import gg.modl.backend.ticket.dto.request.MinecraftCreateTicketRequest;
import gg.modl.backend.ticket.dto.request.QuickResponseRequest;
import gg.modl.backend.ticket.dto.request.SubmitTicketFormRequest;
import gg.modl.backend.settings.service.WebhookSettingsService;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketMongoRepository ticketRepository;

    @Mock
    private StaffMongoRepository staffRepository;

    @Mock
    private QuickResponseSettingsService quickResponseSettingsService;

    @Mock
    private TicketFormSettingsService ticketFormSettingsService;

    @Mock
    private TicketNotificationService notificationService;

    @Mock
    private TicketIdGenerator ticketIdGenerator;

    private final TicketContentService contentService = new TicketContentService();

    @Mock
    private WebhookSettingsService webhookSettingsService;

    @Mock
    private RealtimeEventDispatcher realtimeEventDispatcher;

    private TicketService ticketService;
    private MinecraftTicketService minecraftTicketService;

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(ticketRepository, staffRepository, quickResponseSettingsService, ticketFormSettingsService, notificationService, ticketIdGenerator, contentService, webhookSettingsService, realtimeEventDispatcher);
        minecraftTicketService = new MinecraftTicketService(ticketRepository, notificationService, ticketIdGenerator, realtimeEventDispatcher);
    }

    @Test
    void createMinecraftTicketMapsPluginTypeAndPersistsReply() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        when(ticketIdGenerator.generate(any(Server.class), any(TicketCategory.class))).thenReturn("CHAT-123456");
        when(ticketRepository.saveEntity(any(Server.class), any(Ticket.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));

        Ticket ticket = minecraftTicketService.createMinecraftTicket(server, new MinecraftCreateTicketRequest(
            "uuid-1",
            "PlayerOne",
            "chat",
            "Chat report",
            "reported bad chat",
            "uuid-2",
            "PlayerTwo",
            List.of("hello world"),
            List.of("report"),
            null,
            "survival",
            null
        ));

        assertEquals(TicketCategory.CHAT, ticket.getType());
        assertEquals(TicketPriority.NORMAL, ticket.getPriority());
        assertEquals(1, ticket.getReplies().size());
        assertEquals("reported bad chat", ticket.getReplies().get(0).getContent());
        assertEquals(1, ticket.getChatMessages().size());
    }

    @Test
    void createTicketAcceptsLegacyTypeSpacingAndPriorityAliases() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        when(ticketIdGenerator.generate(any(Server.class), any(TicketCategory.class))).thenReturn("STAFF-123456");
        when(ticketRepository.saveEntity(any(Server.class), any(Ticket.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));

        ticketService.createTicket(server, new CreateTicketRequest(
            "staff application",
            "",
            "Legacy alias submit",
            null,
            "Applicant",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of("legacy"),
            "medium",
            null,
            null,
            null
        ));

        ArgumentCaptor<Ticket> savedTicketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).saveEntity(any(Server.class), savedTicketCaptor.capture());
        Ticket savedTicket = savedTicketCaptor.getValue();

        assertEquals(TicketCategory.APPLICATION, savedTicket.getType());
        assertEquals(TicketPriority.NORMAL, savedTicket.getPriority());
        assertEquals(TicketStatus.UNFINISHED, savedTicket.getStatus());
    }

    @Test
    void claimMinecraftTicketRenamesMatchingNonStaffReplies() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        Ticket ticket = Ticket.builder()
            .id("SUPPORT-123456")
            .creatorName("Old Web User")
            .replies(new ArrayList<>(List.of(
                TicketReply.builder().id("1").name("Old Web User").staff(false).content("first").created(new Date()).build(),
                TicketReply.builder().id("2").name("Staff").staff(true).content("staff").created(new Date()).build()
            )))
            .build();

        when(ticketRepository.findById(server, "SUPPORT-123456")).thenReturn(Optional.of(ticket));
        when(ticketRepository.saveEntity(any(Server.class), any(Ticket.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));

        MinecraftTicketService.MinecraftTicketClaimResult result = minecraftTicketService.claimMinecraftTicket(
            server,
            "SUPPORT-123456",
            new MinecraftClaimTicketRequest("uuid-new", "VerifiedPlayer")
        );

        assertEquals(MinecraftTicketService.MinecraftTicketClaimStatus.SUCCESS, result.status());
        assertEquals("uuid-new", result.ticket().getCreatorUuid());
        assertEquals("VerifiedPlayer", result.ticket().getCreatorName());
        assertEquals("VerifiedPlayer", result.ticket().getReplies().get(0).getName());
        assertEquals("Staff", result.ticket().getReplies().get(1).getName());
        assertNotNull(result.ticket().getUpdatedAt());

        ArgumentCaptor<Ticket> updatedTicketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).saveEntity(any(Server.class), updatedTicketCaptor.capture());
        assertTrue(updatedTicketCaptor.getValue().getUpdatedAt() != null);
    }

    @Test
    void dismissMinecraftReportClosesTicketAndAppendsStaffReply() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        Ticket ticket = Ticket.builder()
            .id("REPORT-1")
            .data(new HashMap<>())
            .replies(new ArrayList<>())
            .build();

        when(ticketRepository.findById(server, "REPORT-1")).thenReturn(Optional.of(ticket));
        when(ticketRepository.saveEntity(any(Server.class), any(Ticket.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));

        MinecraftTicketService.ReportOperationResult result = minecraftTicketService.dismissMinecraftReport(
            server,
            "REPORT-1",
            new DismissReportRequest("Moderator", "Insufficient evidence")
        );

        assertEquals(MinecraftTicketService.ReportOperationStatus.SUCCESS, result.status());

        ArgumentCaptor<Ticket> updatedTicketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).saveEntity(any(Server.class), updatedTicketCaptor.capture());
        Ticket updatedTicket = updatedTicketCaptor.getValue();
        assertEquals(TicketStatus.CLOSED, updatedTicket.getStatus());
        assertTrue(updatedTicket.isLocked());
        assertEquals(1, updatedTicket.getReplies().size());
        assertEquals("Moderator", updatedTicket.getReplies().get(0).getName());
        assertEquals("Insufficient evidence", updatedTicket.getData().get("dismissReason"));
        assertEquals("Moderator", updatedTicket.getData().get("dismissedBy"));
        verify(notificationService).notifyTicketReply(any(Server.class), any(Ticket.class), any(TicketReply.class));
    }

    @Test
    void processQuickResponseClosesTicketThroughRepositorySave() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        Ticket ticket = Ticket.builder()
            .id("SUPPORT-1")
            .replies(new ArrayList<>())
            .notes(new ArrayList<>())
            .build();
        QuickResponseSettings settings = QuickResponseSettings.builder().build();
        QuickResponseSettings.Action action = QuickResponseSettings.Action.builder()
            .id("close")
            .name("Close")
            .message("Resolved")
            .closeTicket(true)
            .appealAction("none")
            .build();

        when(ticketRepository.findById(server, "SUPPORT-1")).thenReturn(Optional.of(ticket));
        when(ticketRepository.saveEntity(any(Server.class), any(Ticket.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(quickResponseSettingsService.getQuickResponseSettings(server)).thenReturn(settings);
        when(quickResponseSettingsService.findAction(settings, "general", "close")).thenReturn(action);

        var result = ticketService.processQuickResponse(
            server,
            "SUPPORT-1",
            new QuickResponseRequest("close", "general", null, null, null, null),
            "Moderator"
        );

        assertTrue(result.success());

        ArgumentCaptor<Ticket> updatedTicketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).saveEntity(any(Server.class), updatedTicketCaptor.capture());
        Ticket updatedTicket = updatedTicketCaptor.getValue();
        assertEquals(TicketStatus.CLOSED, updatedTicket.getStatus());
        assertTrue(updatedTicket.isLocked());
        assertEquals(1, updatedTicket.getReplies().size());
        assertEquals("Moderator", updatedTicket.getReplies().get(0).getName());
        verify(notificationService).notifyTicketReply(any(Server.class), any(Ticket.class), any(TicketReply.class));
        verify(notificationService).notifyTicketClosed(any(Server.class), any(Ticket.class));
    }

    @Test
    void submitTicketFormPromotesUnfinishedTicketAndAddsInitialReply() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        Ticket ticket = Ticket.builder()
            .id("SUPPORT-2")
            .type(TicketCategory.SUPPORT)
            .status(TicketStatus.UNFINISHED)
            .creatorName("PlayerOne")
            .replies(new ArrayList<>())
            .notes(new ArrayList<>())
            .data(new HashMap<>())
            .build();

        when(ticketRepository.findById(server, "SUPPORT-2")).thenReturn(Optional.of(ticket));
        when(ticketRepository.saveEntity(any(Server.class), any(Ticket.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));

        var response = ticketService.submitTicketForm(
            server,
            "SUPPORT-2",
            new SubmitTicketFormRequest(
                "Updated subject",
                "player@example.com",
                java.util.Map.of("issue_type", "Bug report", "emailAuthEnabled", true),
                List.of(),
                "creator-1",
                null
            )
        );

        assertNotNull(response);

        ArgumentCaptor<Ticket> updatedTicketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).saveEntity(any(Server.class), updatedTicketCaptor.capture());
        Ticket updatedTicket = updatedTicketCaptor.getValue();
        assertEquals(TicketStatus.OPEN, updatedTicket.getStatus());
        assertEquals("Updated subject", updatedTicket.getSubject());
        assertTrue(updatedTicket.isEmailAuthEnabled());
        assertEquals(true, updatedTicket.getData().get("emailAuthEnabled"));
        assertEquals(true, updatedTicket.getFormData().get("emailAuthEnabled"));
        assertEquals("player@example.com", updatedTicket.getData().get("creatorEmail"));
        assertEquals("creator-1", updatedTicket.getData().get("creatorIdentifier"));
        assertEquals(1, updatedTicket.getReplies().size());
        assertTrue(updatedTicket.getReplies().get(0).getContent().contains("Issue Type"));
    }

    @Test
    void getMinecraftTicketsByCreatorLowercasesUuidBeforeQueryingRepository() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        when(ticketRepository.findRecentByCreator(any(Server.class), any(), anyInt())).thenReturn(List.of());

        minecraftTicketService.getMinecraftTicketsByCreator(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", 25);

        verify(ticketRepository).findRecentByCreator(server, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 25);
    }

    @Test
    void getMinecraftReportsForPlayerLowercasesUuidBeforeQueryingRepository() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        when(ticketRepository.findReports(any(Server.class), any(), any(), anyInt(), anyBoolean())).thenReturn(List.of());

        minecraftTicketService.getMinecraftReportsForPlayer(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "open", 10);

        verify(ticketRepository).findReports(eq(server), eq("open"), eq("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), eq(10), eq(false));
    }

    @Test
    void submitTicketFormCannotDisableExistingEmailAuthWhileSavingEmail() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        Ticket ticket = Ticket.builder()
            .id("SUPPORT-3")
            .type(TicketCategory.SUPPORT)
            .status(TicketStatus.UNFINISHED)
            .emailAuthEnabled(true)
            .creatorName("PlayerOne")
            .replies(new ArrayList<>())
            .notes(new ArrayList<>())
            .data(new HashMap<>())
            .build();

        when(ticketRepository.findById(server, "SUPPORT-3")).thenReturn(Optional.of(ticket));
        when(ticketRepository.saveEntity(any(Server.class), any(Ticket.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));

        ticketService.submitTicketForm(
            server,
            "SUPPORT-3",
            new SubmitTicketFormRequest(
                "Updated subject",
                "player@example.com",
                java.util.Map.of("issue_type", "Bug report", "emailAuthEnabled", false),
                List.of(),
                "creator-1",
                null
            )
        );

        ArgumentCaptor<Ticket> updatedTicketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).saveEntity(any(Server.class), updatedTicketCaptor.capture());
        Ticket updatedTicket = updatedTicketCaptor.getValue();
        assertTrue(updatedTicket.isEmailAuthEnabled());
        assertEquals(true, updatedTicket.getData().get("emailAuthEnabled"));
        assertEquals("player@example.com", updatedTicket.getData().get("creatorEmail"));
        assertEquals(false, updatedTicket.getFormData().get("emailAuthEnabled"));
    }
}
