package gg.modl.backend.ticket.controller;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.request.AddNoteRequest;
import gg.modl.backend.ticket.dto.request.AddReplyRequest;
import gg.modl.backend.ticket.dto.request.AddTagRequest;
import gg.modl.backend.ticket.dto.request.BulkTicketUpdateRequest;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import gg.modl.backend.ticket.dto.request.QuickResponseRequest;
import gg.modl.backend.ticket.dto.request.UpdateTicketRequest;
import gg.modl.backend.ticket.dto.response.PaginatedTicketsResponse;
import gg.modl.backend.ticket.dto.response.QuickResponseResult;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.service.TicketReplyService;
import gg.modl.backend.ticket.service.TicketSearchService;
import gg.modl.backend.ticket.service.TicketService;
import gg.modl.backend.ticket.service.TicketSubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_TICKETS)
@RequiredArgsConstructor
@Validated
public class PanelTicketController {
    private final TicketService ticketService;
    private final TicketSearchService ticketSearchService;
    private final TicketReplyService ticketReplyService;
    private final TicketSubscriptionService subscriptionService;

    @GetMapping
    public ResponseEntity<PaginatedTicketsResponse> searchTickets(
        @RequestParam(defaultValue = "1") @Min(RequestValidationLimits.PAGINATION_PAGE_MIN) int page,
        @RequestParam(defaultValue = "10") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) List<String> type,
        @RequestParam(required = false) String author,
        @RequestParam(required = false) List<String> labels,
        @RequestParam(required = false) List<String> assignee,
        @RequestParam(defaultValue = "newest") String sort,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        PaginatedTicketsResponse response = ticketSearchService.searchTickets(
            server, page, limit, search, status, type, author, labels, assignee, sort);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/counts")
    public ResponseEntity<Map<String, Long>> getTicketCounts(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> type,
        @RequestParam(required = false) String author,
        @RequestParam(required = false) List<String> labels,
        @RequestParam(required = false) List<String> assignee,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        Map<String, Long> counts = ticketSearchService.getTicketCounts(server, search, type, author, labels, assignee);
        return ResponseEntity.ok(counts);
    }

    @PostMapping("/bulk")
    public ResponseEntity<?> bulkUpdateTickets(
        @RequestBody @Valid BulkTicketUpdateRequest bulkRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        if (bulkRequest.ticketIds() == null || bulkRequest.ticketIds().isEmpty()) {
            throw new ValidationException("No ticket IDs provided");
        }

        int updatedCount = ticketService.bulkUpdateTickets(server, bulkRequest, staffEmail);
        return ResponseEntity.ok(Map.of("updated", updatedCount, "message", "Successfully updated " + updatedCount + " tickets"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> getTicket(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        if (staffEmail != null && !staffEmail.isBlank()) {
            subscriptionService.markTicketAsRead(server, id, staffEmail);
        }

        return ResponseEntity.ok(ticketService.getTicketById(server, id));
    }

    @PostMapping
    public ResponseEntity<TicketResponse> createTicket(
        @RequestBody @Valid CreateTicketRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        TicketResponse ticket = ticketService.createTicket(server, createRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ticket);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> updateTicket(
        @PathVariable String id,
        @RequestBody @Valid UpdateTicketRequest updateRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        TicketResponse ticket = ticketService.updateTicket(server, id, updateRequest, staffEmail);
        return ResponseEntity.ok(Map.of(
            "id", ticket.id(),
            "status", ticket.status(),
            "tags", ticket.tags(),
            "notes", ticket.notes(),
            "messages", ticket.messages(),
            "data", ticket.data() != null ? ticket.data() : Map.of(),
            "locked", ticket.locked(),
            "assignedTo", ticket.assignedTo() != null ? ticket.assignedTo() : List.of()
        ));
    }

    @PostMapping("/{id}/notes")
    public ResponseEntity<?> addNote(
        @PathVariable String id,
        @RequestBody @Valid AddNoteRequest noteRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(ticketReplyService.addNote(server, id, noteRequest));
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<?> addReply(
        @PathVariable String id,
        @RequestBody @Valid AddReplyRequest replyRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        TicketReply reply = ticketReplyService.addReply(server, id, replyRequest);

        if (replyRequest.staff() && staffEmail != null && !staffEmail.isBlank()) {
            subscriptionService.ensureSubscription(server, id, staffEmail);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(reply);
    }

    @PostMapping("/{id}/tags")
    public ResponseEntity<?> addTag(
        @PathVariable String id,
        @RequestBody @Valid AddTagRequest tagRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        return ResponseEntity.ok(ticketReplyService.addTag(server, id, tagRequest.tag()));
    }

    @DeleteMapping("/{id}/tags/{tag}")
    public ResponseEntity<?> removeTag(
        @PathVariable String id,
        @PathVariable String tag,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        return ResponseEntity.ok(ticketReplyService.removeTag(server, id, tag));
    }

    @GetMapping("/player/{uuid}")
    public ResponseEntity<?> getTicketsByPlayer(
        @PathVariable String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<Ticket> tickets = ticketSearchService.getTicketsByPlayer(server, uuid);
        return ResponseEntity.ok(tickets);
    }

    @GetMapping("/tag/{tag}")
    public ResponseEntity<?> getTicketsByTag(
        @PathVariable String tag,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<Ticket> tickets = ticketSearchService.getTicketsByTag(server, tag);
        return ResponseEntity.ok(tickets);
    }

    @PostMapping("/{id}/quick-response")
    public ResponseEntity<?> quickResponse(
        @PathVariable String id,
        @RequestBody @Valid QuickResponseRequest quickRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        String staffUsername = staffEmail != null ? staffEmail.split("@")[0] : "System";

        QuickResponseResult result = ticketService.processQuickResponse(server, id, quickRequest, staffUsername);

        if (!result.success()) {
            return ResponseEntity.badRequest().body(Map.of("error", result.message()));
        }

        return ResponseEntity.ok(result);
    }
}
