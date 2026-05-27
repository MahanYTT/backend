package gg.modl.backend.ticket.controller;

import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.dto.response.SubscriptionUpdateResponse;
import gg.modl.backend.ticket.dto.response.TicketSubscriptionResponse;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.ticket.service.TicketSubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_TICKET_SUBSCRIPTIONS)
@RequiredArgsConstructor
@Validated
public class TicketSubscriptionController {
    private final TicketSubscriptionService subscriptionService;

    @GetMapping
    public ResponseEntity<List<TicketSubscriptionResponse>> getSubscriptions(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        if (staffEmail == null || staffEmail.isBlank()) {
            return ResponseEntity.status(401).build();
        }

        List<TicketSubscriptionResponse> subscriptions = subscriptionService.getSubscriptions(server, staffEmail);
        return ResponseEntity.ok(subscriptions);
    }

    @DeleteMapping("/{ticketId}")
    public ResponseEntity<?> unsubscribe(
        @PathVariable String ticketId,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        if (staffEmail == null || staffEmail.isBlank()) {
            throw new UnauthorizedException("Not authenticated");
        }

        if (!subscriptionService.unsubscribe(server, staffEmail, ticketId)) {
            throw new ResourceNotFoundException("Subscription not found");
        }
        return ResponseEntity.ok(Map.of("message", "Successfully unsubscribed from ticket"));
    }

    @GetMapping("/updates")
    public ResponseEntity<List<SubscriptionUpdateResponse>> getUpdates(
        @RequestParam(defaultValue = "10") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        if (staffEmail == null || staffEmail.isBlank()) {
            return ResponseEntity.status(401).build();
        }

        List<SubscriptionUpdateResponse> updates = subscriptionService.getUpdates(server, staffEmail, limit);
        return ResponseEntity.ok(updates);
    }

    @PostMapping("/updates/{updateId}/read")
    public ResponseEntity<?> markAsRead(
        @PathVariable String updateId,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        if (staffEmail == null || staffEmail.isBlank()) {
            throw new UnauthorizedException("Not authenticated");
        }

        boolean result = subscriptionService.markAsRead(server, staffEmail, updateId);
        return ResponseEntity.ok(Map.of("message", "Update marked as read", "modified", result));
    }

    @PostMapping("/tickets/{ticketId}/read")
    public ResponseEntity<?> markTicketAsRead(
        @PathVariable String ticketId,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        if (staffEmail == null || staffEmail.isBlank()) {
            throw new UnauthorizedException("Not authenticated");
        }

        subscriptionService.markTicketAsRead(server, ticketId, staffEmail);
        return ResponseEntity.ok(Map.of("message", "All updates for ticket marked as read"));
    }

    @GetMapping("/assigned-updates")
    public ResponseEntity<List<SubscriptionUpdateResponse>> getAssignedUpdates(
        @RequestParam(defaultValue = "10") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffEmail = RequestUtil.getSessionEmail(request);

        if (staffEmail == null || staffEmail.isBlank()) {
            return ResponseEntity.status(401).build();
        }

        List<SubscriptionUpdateResponse> updates = subscriptionService.getAssignedTicketUpdates(server, staffEmail, limit);
        return ResponseEntity.ok(updates);
    }
}
