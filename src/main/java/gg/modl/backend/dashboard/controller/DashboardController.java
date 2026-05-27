package gg.modl.backend.dashboard.controller;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.dashboard.dto.response.ActivityItemResponse;
import gg.modl.backend.dashboard.dto.response.DashboardMetricsResponse;
import gg.modl.backend.dashboard.dto.response.RecentPunishmentResponse;
import gg.modl.backend.dashboard.dto.response.RecentTicketResponse;
import gg.modl.backend.dashboard.service.DashboardService;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_DASHBOARD)
@RequiredArgsConstructor
@Validated
public class DashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/metrics")
    public ResponseEntity<DashboardMetricsResponse> getMetrics(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        DashboardMetricsResponse metrics = dashboardService.getMetrics(server);
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/recent-tickets")
    public ResponseEntity<List<RecentTicketResponse>> getRecentTickets(
        @RequestParam(defaultValue = "10") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<RecentTicketResponse> tickets = dashboardService.getRecentTickets(server, limit);
        return ResponseEntity.ok(tickets);
    }

    @GetMapping("/recent-punishments")
    public ResponseEntity<List<RecentPunishmentResponse>> getRecentPunishments(
        @RequestParam(defaultValue = "10") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<RecentPunishmentResponse> punishments = dashboardService.getRecentPunishments(server, limit);
        return ResponseEntity.ok(punishments);
    }

    @GetMapping("/activity/recent")
    public ResponseEntity<?> getRecentActivity(
        @RequestParam(defaultValue = "20") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        AuthSessionData session = RequestUtil.getSession(request);

        if (session == null || session.getEmail() == null) {
            throw new UnauthorizedException("Not authenticated");
        }

        String staffEmail = session.getEmail();
        List<ActivityItemResponse> activities = dashboardService.getRecentActivity(server, staffEmail, limit, days);
        return ResponseEntity.ok(activities);
    }
}
