package gg.modl.backend.audit.controller;

import gg.modl.backend.audit.dto.request.BulkPunishmentActionRequest;
import gg.modl.backend.audit.dto.request.DateRangeRollbackRequest;
import gg.modl.backend.audit.dto.request.RollbackRequest;
import gg.modl.backend.audit.dto.response.ActivePunishmentResponse;
import gg.modl.backend.audit.dto.response.PunishmentAuditResponse;
import gg.modl.backend.audit.dto.response.StaffDetailsResponse;
import gg.modl.backend.audit.dto.response.StaffPerformanceResponse;
import gg.modl.backend.audit.service.AuditService;
import gg.modl.backend.audit.service.StaffPerformanceService;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_AUDIT)
@RequiredArgsConstructor
@Validated
public class AuditController {
    private final AuditService auditService;
    private final StaffPerformanceService staffPerformanceService;
    private final PermissionService permissionService;

    private static final Set<String> ALLOWED_TABLES = Set.of(
        CollectionName.MODL_SERVERS,
        CollectionName.PLAYERS,
        CollectionName.SESSIONS,
        CollectionName.AUTH_CODES,
        CollectionName.SETTINGS,
        CollectionName.STAFF,
        CollectionName.STAFF_ROLES,
        CollectionName.INVITATIONS,
        CollectionName.TICKETS,
        CollectionName.TICKET_VERIFICATIONS,
        CollectionName.LOGS,
        CollectionName.KNOWLEDGEBASE_CATEGORIES,
        CollectionName.KNOWLEDGEBASE_ARTICLES,
        CollectionName.HOMEPAGE_CARDS
    );

    @GetMapping("/staff-performance")
    public ResponseEntity<List<StaffPerformanceResponse>> getStaffPerformance(
        @RequestParam(defaultValue = "30d") String period,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);
        final List<StaffPerformanceResponse> performance = staffPerformanceService.getStaffPerformance(server, period);
        return ResponseEntity.ok(performance);
    }

    @GetMapping("/staff/{username}/details")
    public ResponseEntity<StaffDetailsResponse> getStaffDetails(
        @PathVariable String username,
        @RequestParam(defaultValue = "30d") String period,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);
        final StaffDetailsResponse details = staffPerformanceService.getStaffDetails(server, username, period);
        return ResponseEntity.ok(details);
    }

    @GetMapping("/punishments/active")
    public ResponseEntity<List<ActivePunishmentResponse>> getActivePunishments(
        @RequestParam(defaultValue = "active") String status,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);
        final List<ActivePunishmentResponse> punishments = auditService.getPunishmentsList(server, status);
        return ResponseEntity.ok(punishments);
    }

    @GetMapping("/punishments")
    public ResponseEntity<List<PunishmentAuditResponse>> getPunishments(
        @RequestParam(defaultValue = "50") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        @RequestParam(defaultValue = "false") boolean canRollback,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);
        final List<PunishmentAuditResponse> punishments = auditService.getPunishments(server, limit, canRollback);
        return ResponseEntity.ok(punishments);
    }

    @PostMapping({"/punishments/{id}/rollback", "/punishment/{id}/rollback"})
    public ResponseEntity<?> rollbackPunishment(
        @PathVariable String id,
        @RequestBody(required = false) @Valid RollbackRequest rollbackRequest,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);
        final String performerUsername = RequestUtil.getCurrentUsername(request);

        final String reason = rollbackRequest != null ? rollbackRequest.reason() : "Admin rollback";
        final boolean success = auditService.rollbackPunishment(server, id, reason, performerUsername);

        if (success) {
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Punishment rolled back successfully"
            ));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/staff/{username}/rollback-all")
    public ResponseEntity<?> rollbackAllByStaff(
        @PathVariable String username,
        @RequestBody(required = false) @Valid RollbackRequest rollbackRequest,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);
        final String performerUsername = RequestUtil.getCurrentUsername(request);

        final String reason = rollbackRequest != null ? rollbackRequest.reason() : "Bulk rollback by admin";
        final int count = auditService.rollbackAllPunishmentsByStaff(server, username, reason, performerUsername);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "count", count,
            "message", "Successfully rolled back " + count + " punishments"
        ));
    }

    @PostMapping("/staff/{username}/rollback-date-range")
    public ResponseEntity<?> rollbackByDateRange(
        @PathVariable String username,
        @RequestBody @Valid DateRangeRollbackRequest rollbackRequest,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);
        final String performerUsername = RequestUtil.getCurrentUsername(request);

        if (rollbackRequest.startDate() == null || rollbackRequest.endDate() == null) {
            throw new ValidationException("Start date and end date are required");
        }

        final String reason = rollbackRequest.reason() != null ? rollbackRequest.reason() : "Bulk rollback by admin";
        final int count = auditService.rollbackPunishmentsByDateRange(
            server,
            username,
            rollbackRequest.startDate(),
            rollbackRequest.endDate(),
            reason,
            performerUsername
        );

        return ResponseEntity.ok(Map.of(
            "success", true,
            "count", count,
            "message", "Successfully rolled back " + count + " punishments"
        ));
    }

    @PostMapping("/punishments/bulk-pardon")
    public ResponseEntity<?> bulkPardon(
        @RequestBody @Valid BulkPunishmentActionRequest actionRequest,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);
        requireSuperAdmin(server, request);

        final String performerUsername = RequestUtil.getCurrentUsername(request);
        final int count = auditService.bulkPardonByType(
            server, actionRequest.typeOrdinals(), actionRequest.reason(), performerUsername);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "count", count,
            "message", "Successfully pardoned " + count + " punishments"
        ));
    }

    @PostMapping("/punishments/bulk-set-expiration")
    public ResponseEntity<?> bulkSetExpiration(
        @RequestBody @Valid BulkPunishmentActionRequest actionRequest,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);
        requireSuperAdmin(server, request);

        if (actionRequest.newDurationMs() == null) {
            throw new ValidationException("newDurationMs is required for set-expiration");
        }

        final String performerUsername = RequestUtil.getCurrentUsername(request);
        final int count = auditService.bulkSetExpirationByType(
            server, actionRequest.typeOrdinals(), actionRequest.newDurationMs(),
            actionRequest.reason(), performerUsername);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "count", count,
            "message", "Successfully updated expiration for " + count + " punishments"
        ));
    }

    private void requireSuperAdmin(Server server, HttpServletRequest request) {
        final String email = RequestUtil.getSessionEmail(request);
        if (!permissionService.isSuperAdmin(server, email)) {
            throw new ForbiddenException("Only super admins can perform this action");
        }
    }

    @GetMapping("/database/{table}")
    public ResponseEntity<?> getDatabaseTable(
        @PathVariable String table,
        @RequestParam(defaultValue = "100") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int skip,
        HttpServletRequest request
    ) {
        final Server server = RequestUtil.getRequestServer(request);

        if (!ALLOWED_TABLES.contains(table)) {
            throw new ValidationException("Invalid table name");
        }

        final Map<String, Object> result = auditService.getDatabaseTable(server, table, limit, skip);
        return ResponseEntity.ok(result);
    }
}
