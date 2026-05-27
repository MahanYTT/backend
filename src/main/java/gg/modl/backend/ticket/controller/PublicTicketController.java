package gg.modl.backend.ticket.controller;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.request.AddReplyRequest;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import gg.modl.backend.ticket.dto.request.SubmitTicketFormRequest;
import gg.modl.backend.ticket.dto.request.VerifyTicketCodeRequest;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.service.TicketEmailVerificationService;
import gg.modl.backend.ticket.service.TicketReplyService;
import gg.modl.backend.ticket.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_TICKETS)
@RequiredArgsConstructor
public class PublicTicketController {
    private final TicketService ticketService;
    private final TicketReplyService ticketReplyService;
    private final TicketEmailVerificationService verificationService;
    private static final String CREATOR_EMAIL_DATA_KEY = "creatorEmail";
    private static final String CREATOR_IDENTIFIER_DATA_KEY = "creatorIdentifier";
    private static final String EMAIL_AUTH_DATA_KEY = "emailAuthEnabled";
    private static final String CONTACT_EMAIL_DATA_KEY = "contactEmail";
    private static final String CONTACT_EMAIL_LEGACY_DATA_KEY = "contact_email";
    private static final String EMAIL_DATA_KEY = "email";
    private static final String PLAYER_UUID_DATA_KEY = "playerUuid";

    @PostMapping
    public ResponseEntity<?> createTicket(
        @RequestBody @Valid CreateTicketRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        TicketResponse ticket = ticketService.createTicket(server, createRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "success", true,
            "ticketId", ticket.id(),
            "message", "Ticket created successfully",
            "ticket", Map.of(
                "id", ticket.id(),
                "type", ticket.type(),
                "subject", ticket.subject(),
                "status", ticket.status(),
                "created", ticket.date().toInstant().toString()
            )
        ));
    }

    @PostMapping("/unfinished")
    public ResponseEntity<?> createUnfinishedTicket(
        @RequestBody @Valid CreateTicketRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        TicketResponse ticket = ticketService.createTicket(server, createRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "success", true,
            "ticketId", ticket.id(),
            "message", "Ticket created successfully (Unfinished)",
            "ticket", Map.of(
                "id", ticket.id(),
                "type", ticket.type(),
                "subject", ticket.subject(),
                "status", ticket.status(),
                "created", ticket.date().toInstant().toString()
            )
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTicket(
        @PathVariable String id,
        @RequestParam(value = "token", required = false) String ticketToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        Optional<Ticket> rawTicket = ticketService.getTicketRaw(server, id);
        if (rawTicket.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Ticket ticket = rawTicket.get();

        if (ticket.isHidden()) {
            return ResponseEntity.notFound().build();
        }

        if (ticket.isEmailAuthEnabled()) {
            if (ticketToken == null || !verificationService.validateToken(server, id, ticketToken)) {
                String emailHint = ticketService.getEmailHint(ticket);
                Map<String, Object> body = new HashMap<>();
                body.put("requiresVerification", true);
                body.put("emailHint", emailHint != null ? emailHint : "");
                body.put("ticketId", id);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
            }
        }

        TicketResponse ticketResponse = ticketService.getTicketById(server, id);
        Map<String, Object> response = new HashMap<>();
        response.put("id", ticketResponse.id());
        response.put("_id", ticketResponse.id());
        response.put("type", ticketResponse.type());
        response.put("subject", ticketResponse.subject());
        response.put("status", ticketResponse.status());
        String creatorName = ticketResponse.creatorName() != null ? ticketResponse.creatorName() : "";
        response.put("creatorName", creatorName);
        response.put("creator", creatorName);
        response.put("created", ticketResponse.date());
        response.put("date", ticketResponse.date());
        response.put("category", ticketResponse.category());
        response.put("locked", ticketResponse.locked());
        response.put("creatorUuid", "");
        response.put("reportedBy", ticketResponse.reportedBy() != null ? ticketResponse.reportedBy() : "");
        List<Map<String, Object>> publicMessages = filterPublicReplies(ticketResponse.messages());
        response.put("replies", publicMessages);
        response.put("messages", publicMessages);
        response.put("data", filterPublicData(ticketResponse.data()));
        response.put("formData", filterPublicData(ticketResponse.formData()));
        response.put("reportedPlayer", ticketResponse.reportedPlayer() != null ? ticketResponse.reportedPlayer() : "");
        response.put("reportedPlayerUuid", "");
        response.put("chatMessages", ticket.isEmailAuthEnabled() && ticketResponse.chatMessages() != null ? ticketResponse.chatMessages() : Collections.emptyList());
        response.put("emailAuthEnabled", ticketResponse.emailAuthEnabled());
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> filterPublicData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> filtered = new HashMap<>(data);
        filtered.remove(CREATOR_EMAIL_DATA_KEY);
        filtered.remove(CREATOR_IDENTIFIER_DATA_KEY);
        filtered.remove(EMAIL_AUTH_DATA_KEY);
        filtered.remove(CONTACT_EMAIL_DATA_KEY);
        filtered.remove(CONTACT_EMAIL_LEGACY_DATA_KEY);
        filtered.remove(EMAIL_DATA_KEY);
        filtered.remove(PLAYER_UUID_DATA_KEY);
        return filtered;
    }

    private List<Map<String, Object>> filterPublicReplies(List<TicketReply> replies) {
        if (replies == null || replies.isEmpty()) {
            return Collections.emptyList();
        }
        return replies.stream().map(this::toPublicReply).toList();
    }

    private Map<String, Object> toPublicReply(TicketReply reply) {
        Map<String, Object> publicReply = new HashMap<>();
        publicReply.put("id", reply.getId());
        publicReply.put("name", reply.getName());
        publicReply.put("avatar", reply.getAvatar());
        publicReply.put("content", reply.getContent());
        publicReply.put("type", reply.getType());
        publicReply.put("created", reply.getCreated());
        publicReply.put("staff", reply.isStaff());
        publicReply.put("action", reply.getAction());
        publicReply.put("attachments", reply.getAttachments() != null ? reply.getAttachments() : Collections.emptyList());
        return publicReply;
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> getTicketStatus(
        @PathVariable String id,
        @RequestParam(value = "token", required = false) String ticketToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        Optional<Ticket> rawTicket = ticketService.getTicketRaw(server, id);
        if (rawTicket.isEmpty() || rawTicket.get().isHidden()) {
            return ResponseEntity.notFound().build();
        }

        Ticket ticket = rawTicket.get();
        if (ticket.isEmailAuthEnabled()) {
            if (ticketToken == null || !verificationService.validateToken(server, id, ticketToken)) {
                throw new ForbiddenException("Email verification required");
            }
        }

        TicketResponse ticketResp = ticketService.getTicketById(server, id);
        return ResponseEntity.ok(Map.of(
            "id", ticketResp.id(),
            "type", ticketResp.type(),
            "subject", ticketResp.subject(),
            "status", ticketResp.status(),
            "created", ticketResp.date(),
            "locked", ticketResp.locked()
        ));
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<?> addReply(
        @PathVariable String id,
        @RequestBody @Valid AddReplyRequest replyRequest,
        @RequestParam(value = "token", required = false) String ticketToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        Optional<Ticket> rawTicket = ticketService.getTicketRaw(server, id);
        if (rawTicket.isEmpty() || rawTicket.get().isHidden()) {
            return ResponseEntity.notFound().build();
        }

        Ticket ticket = rawTicket.get();
        if (ticket.isEmailAuthEnabled()) {
            if (ticketToken == null || !verificationService.validateToken(server, id, ticketToken)) {
                throw new ForbiddenException("Email verification required");
            }
        }

        if (replyRequest.staff()) {
            throw new ValidationException("Public replies cannot be marked as staff");
        }

        TicketReply reply = ticketReplyService.addReply(server, id, replyRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "success", true,
            "message", "Reply added successfully",
            "reply", reply
        ));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submitTicketForm(
        @PathVariable String id,
        @RequestBody @Valid SubmitTicketFormRequest submitRequest,
        @RequestParam(value = "token", required = false) String ticketToken,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        Optional<Ticket> rawTicket = ticketService.getTicketRaw(server, id);
        if (rawTicket.isEmpty() || rawTicket.get().isHidden()) {
            return ResponseEntity.notFound().build();
        }

        Ticket ticket = rawTicket.get();
        if (ticket.isEmailAuthEnabled() && ticketService.getEmailHint(ticket) != null) {
            if (ticketToken == null || !verificationService.validateToken(server, id, ticketToken)) {
                throw new ForbiddenException("Email verification required");
            }
        }

        TicketResponse ticketResp = ticketService.submitTicketForm(server, id, submitRequest);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Ticket submitted successfully",
            "ticket", Map.of(
                "id", ticketResp.id(),
                "subject", ticketResp.subject(),
                "status", ticketResp.status()
            )
        ));
    }

    @PostMapping("/{id}/request-verification")
    public ResponseEntity<?> requestVerification(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        Optional<Ticket> rawTicket = ticketService.getTicketRaw(server, id);
        if (rawTicket.isEmpty() || rawTicket.get().isHidden()) {
            return ResponseEntity.notFound().build();
        }

        Ticket ticket = rawTicket.get();
        if (!ticket.isEmailAuthEnabled()) {
            throw new ValidationException("Email auth is not enabled for this ticket");
        }

        String emailHint = verificationService.sendVerificationCode(server, ticket);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Verification code sent",
            "emailHint", emailHint
        ));
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<?> verifyCode(
        @PathVariable String id,
        @RequestBody @Valid VerifyTicketCodeRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        String token = verificationService.verifyCode(server, id, body.code());
        if (token == null) {
            throw new ForbiddenException("Invalid or expired code");
        }

        return ResponseEntity.ok(Map.of(
            "success", true,
            "token", token,
            "message", "Verification successful"
        ));
    }

}
