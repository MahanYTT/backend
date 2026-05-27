package gg.modl.backend.ticket.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.Ticket.ChatMessage;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.service.TicketEmailVerificationService;
import gg.modl.backend.ticket.service.TicketReplyService;
import gg.modl.backend.ticket.service.TicketService;
import java.util.Date;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PublicTicketControllerTest {
    @Mock private TicketService ticketService;
    @Mock private TicketReplyService ticketReplyService;
    @Mock private TicketEmailVerificationService verificationService;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        mockMvc = MockMvcBuilders
            .standaloneSetup(new PublicTicketController(ticketService, ticketReplyService, verificationService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void getTicketStatusRequiresTokenForEmailAuthTicket() throws Exception {
        Ticket ticket = Ticket.builder()
            .id("TICKET-1")
            .emailAuthEnabled(true)
            .build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/status"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(verificationService);
    }

    @Test
    void getTicketStatusAllowsValidTokenForEmailAuthTicket() throws Exception {
        Ticket ticket = Ticket.builder()
            .id("TICKET-1")
            .emailAuthEnabled(true)
            .build();
        Date created = new Date(1_700_000_000_000L);
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        when(verificationService.validateToken(server, "TICKET-1", "valid-token")).thenReturn(true);
        when(ticketService.getTicketById(server, "TICKET-1")).thenReturn(new TicketResponse(
            "TICKET-1",
            "support",
            "support",
            "Need help",
            "open",
            null,
            "PlayerOne",
            null,
            null,
            null,
            null,
            created,
            false,
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            null,
            true,
            false,
            null,
            List.of()
        ));

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/status")
                .param("token", "valid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("TICKET-1"))
            .andExpect(jsonPath("$.subject").value("Need help"));
    }

    @Test
    void getTicketReturnsPublicConversationAndSubmittedDataAfterVerification() throws Exception {
        Ticket ticket = Ticket.builder()
            .id("TICKET-1")
            .emailAuthEnabled(true)
            .data(Map.of("creatorEmail", "player@example.com"))
            .build();
        Date created = new Date(1_700_000_000_000L);
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        when(verificationService.validateToken(server, "TICKET-1", "valid-token")).thenReturn(true);
        when(ticketService.getTicketById(server, "TICKET-1")).thenReturn(new TicketResponse(
            "TICKET-1",
            "support",
            "Support",
            "Need help",
            "open",
            null,
            "PlayerOne",
            "uuid-1",
            "PlayerOne",
            "Reported",
            "uuid-2",
            created,
            false,
            List.of(TicketReply.builder().id("reply-1").content("hello").staff(false).creatorIdentifier("browser-secret").build()),
            List.of(),
            List.of("tag"),
            Map.of(
                "description", "Submitted form",
                "email", "player@example.com",
                "contact_email", "legacy@example.com",
                "creatorEmail", "player@example.com",
                "creatorIdentifier", "browser-secret",
                "emailAuthEnabled", true,
                "contactEmail", "appeal@example.com",
                "playerUuid", "uuid-appeal"
            ),
            Map.of(
                "creatorEmail", "player@example.com",
                "creatorIdentifier", "browser-secret",
                "emailAuthEnabled", true,
                "contactEmail", "appeal@example.com",
                "email", "player@example.com",
                "contact_email", "legacy@example.com",
                "playerUuid", "uuid-appeal"
            ),
            List.of(new ChatMessage("message", created)),
            null,
            true,
            false,
            null,
            List.of()
        ));

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1")
                .param("token", "valid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replies[0].content").value("hello"))
            .andExpect(jsonPath("$.messages[0].content").value("hello"))
            .andExpect(jsonPath("$.formData.description").value("Submitted form"))
            .andExpect(jsonPath("$.formData.creatorEmail").doesNotExist())
            .andExpect(jsonPath("$.formData.creatorIdentifier").doesNotExist())
            .andExpect(jsonPath("$.formData.emailAuthEnabled").doesNotExist())
            .andExpect(jsonPath("$.formData.contactEmail").doesNotExist())
            .andExpect(jsonPath("$.formData.email").doesNotExist())
            .andExpect(jsonPath("$.formData.contact_email").doesNotExist())
            .andExpect(jsonPath("$.formData.playerUuid").doesNotExist())
            .andExpect(jsonPath("$.data.creatorEmail").doesNotExist())
            .andExpect(jsonPath("$.data.creatorIdentifier").doesNotExist())
            .andExpect(jsonPath("$.data.emailAuthEnabled").doesNotExist())
            .andExpect(jsonPath("$.data.contactEmail").doesNotExist())
            .andExpect(jsonPath("$.data.email").doesNotExist())
            .andExpect(jsonPath("$.data.contact_email").doesNotExist())
            .andExpect(jsonPath("$.data.playerUuid").doesNotExist())
            .andExpect(jsonPath("$.replies[0].creatorIdentifier").doesNotExist())
            .andExpect(jsonPath("$.creatorUuid").value(""))
            .andExpect(jsonPath("$.reportedPlayer").value("Reported"))
            .andExpect(jsonPath("$.reportedPlayerUuid").value(""))
            .andExpect(jsonPath("$.chatMessages[0].content").value("message"));
    }

    @Test
    void getTicketHidesRawChatMessagesWhenTicketDoesNotRequireEmailVerification() throws Exception {
        Ticket ticket = Ticket.builder()
            .id("TICKET-1")
            .emailAuthEnabled(false)
            .build();
        Date created = new Date(1_700_000_000_000L);
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        when(ticketService.getTicketById(server, "TICKET-1")).thenReturn(new TicketResponse(
            "TICKET-1",
            "support",
            "Support",
            "Need help",
            "open",
            null,
            "PlayerOne",
            "uuid-1",
            "PlayerOne",
            "Reported",
            "uuid-2",
            created,
            false,
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            List.of(new ChatMessage("private chat line", created)),
            null,
            false,
            false,
            null,
            List.of()
        ));

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.creatorUuid").value(""))
            .andExpect(jsonPath("$.reportedPlayer").value("Reported"))
            .andExpect(jsonPath("$.reportedPlayerUuid").value(""))
            .andExpect(jsonPath("$.chatMessages").isEmpty());
    }

    @Test
    void submitTicketFormAllowsEmailAuthTicketWithoutTokenBeforeEmailIsStored() throws Exception {
        Ticket ticket = Ticket.builder()
            .id("TICKET-1")
            .emailAuthEnabled(true)
            .data(Map.of())
            .build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        when(ticketService.submitTicketForm(server, "TICKET-1", new gg.modl.backend.ticket.dto.request.SubmitTicketFormRequest(
            "Need help",
            null,
            null,
            null,
            null,
            null
        ))).thenReturn(new TicketResponse(
            "TICKET-1",
            "support",
            "Support",
            "Need help",
            "open",
            null,
            "PlayerOne",
            null,
            null,
            null,
            null,
            new Date(),
            false,
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            null,
            true,
            false,
            null,
            List.of()
        ));

        mockMvc.perform(post(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Need help\"}"))
            .andExpect(status().isOk());

        verify(verificationService, never()).validateToken(server, "TICKET-1", null);
    }
}
