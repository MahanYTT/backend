package gg.modl.backend.admin.controller;

import gg.modl.backend.admin.dto.request.UpdateServerRequest;
import gg.modl.backend.admin.service.AdminServerService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.util.PaginationHelper;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.server.data.Server;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.ADMIN_SERVERS)
@RequiredArgsConstructor
@Slf4j
@Validated
public class AdminServerController {
    private final AdminServerService serverService;

    @GetMapping
    public ResponseEntity<?> getServers(
        @RequestParam(defaultValue = "1") @Min(RequestValidationLimits.PAGINATION_PAGE_MIN) int page,
        @RequestParam(defaultValue = "20") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String plan,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "createdAt") String sort,
        @RequestParam(defaultValue = "desc") String order) {

        int pageNum = PaginationHelper.normalizePage(page);
        int limitNum = PaginationHelper.normalizeLimit(limit, 50);
        int skip = PaginationHelper.calculateSkip(page, limitNum);

        List<Server> servers = serverService.findServers(search, plan, status, sort, order, skip, limitNum);
        long total = serverService.countServers(search, plan, status);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of(
                "servers", servers,
                "pagination", Map.of(
                    "page", pageNum,
                    "limit", limitNum,
                    "total", total,
                    "pages", PaginationHelper.calculateTotalPages(total, limitNum)
                )
            )
        ));
    }

    @PostMapping("/usage/batch")
    public ResponseEntity<?> getUsageBatch(@RequestBody @Valid UsageBatchRequest request) {
        if (request.serverIds() == null || request.serverIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Missing required field: serverIds"));
        }

        if (request.serverIds().size() > 50) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Maximum 50 server IDs per request"));
        }

        boolean forceRefresh = Boolean.TRUE.equals(request.forceRefresh());
        Map<String, AdminServerService.UsageSummary> usage = serverService.getUsageStatsForServerIds(request.serverIds(), forceRefresh);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of("usage", usage)
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getServer(@PathVariable String id) {
        return serverService.findById(id)
            .map(server -> ResponseEntity.ok(Map.of("success", true, "data", server)))
            .orElse(ResponseEntity.status(404).body(Map.of("success", false, "error", "Server not found")));
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<?> getServerStats(@PathVariable String id) {
        return serverService.findById(id)
            .map(server -> {
                Map<String, Object> stats = serverService.getServerStats(server);
                return ResponseEntity.ok(Map.of("success", true, "data", stats));
            })
            .orElse(ResponseEntity.status(404).body(Map.of("success", false, "error", "Server not found")));
    }

    @PostMapping
    public ResponseEntity<?> createServer(@RequestBody @Valid CreateServerRequest request) {
        Server saved = serverService.createServer(request.serverName(), request.customDomain(), request.adminEmail(), request.plan());
        return ResponseEntity.status(201).body(Map.of(
            "success", true,
            "data", saved,
            "message", "Server created successfully"
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateServer(@PathVariable String id, @RequestBody @Valid UpdateServerRequest request) {
        if (serverService.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Server not found"));
        }

        Map<String, Object> updateData = new HashMap<>();
        if (request.adminEmail() != null) {
            updateData.put("adminEmail", request.adminEmail());
        }
        if (request.emailVerified() != null) {
            updateData.put("emailVerified", request.emailVerified());
        }
        if (request.provisioningStatus() != null) {
            updateData.put("provisioningStatus", request.provisioningStatus());
        }
        if (request.provisioningNotes() != null) {
            updateData.put("provisioningNotes", request.provisioningNotes());
        }
        if (request.plan() != null) {
            updateData.put("plan", request.plan());
        }
        if (request.subscriptionStatus() != null) {
            updateData.put("subscriptionStatus", request.subscriptionStatus());
        }
        if (request.lastActivityAt() != null) {
            updateData.put("lastActivityAt", request.lastActivityAt());
        }
        updateData.put("updatedAt", new Date());

        Server updated = serverService.updateById(id, updateData);
        if (updated != null) {
            return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "Server updated successfully"));
        }
        return ResponseEntity.status(500).body(Map.of("success", false, "error", "Failed to update server"));
    }

    @PutMapping("/{id}/stats")
    public ResponseEntity<?> updateServerStats(@PathVariable String id, @RequestBody @Valid UpdateStatsRequest request) {
        if (serverService.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Server not found"));
        }

        Map<String, Object> updateData = new HashMap<>();
        if (request.lastActivityAt() != null) {
            updateData.put("lastActivityAt", request.lastActivityAt());
        }
        updateData.put("updatedAt", new Date());

        Server updated = serverService.updateById(id, updateData);
        if (updated != null) {
            return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "Server activity updated successfully"));
        }
        return ResponseEntity.status(500).body(Map.of("success", false, "error", "Failed to update server"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteServer(@PathVariable String id) {
        if (serverService.deleteById(id)) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Server deleted successfully"));
        }
        return ResponseEntity.status(404).body(Map.of("success", false, "error", "Server not found"));
    }

    @PostMapping("/bulk")
    public ResponseEntity<?> bulkOperation(@RequestBody @Valid BulkOperationRequest request) {
        if (request.action() == null || request.serverIds() == null || request.serverIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Missing required fields: action, serverIds"));
        }

        long affectedCount = switch (request.action()) {
            case "delete" -> serverService.bulkDelete(request.serverIds());
            case "suspend" -> serverService.bulkSuspend(request.serverIds());
            case "activate" -> serverService.bulkActivate(request.serverIds());
            case "update-plan" -> {
                if (request.parameters() == null || !request.parameters().containsKey("plan")) {
                    yield -1L;
                }
                yield serverService.bulkUpdatePlan(request.serverIds(), (String) request.parameters().get("plan"));
            }
            default -> -1L;
        };

        if (affectedCount < 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Invalid action or missing parameters"));
        }

        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of("action", request.action(), "affectedCount", affectedCount, "serverIds", request.serverIds()),
            "message", "Bulk operation '" + request.action() + "' completed successfully"
        ));
    }

    @PostMapping("/{id}/reset-database")
    public ResponseEntity<?> resetDatabase(@PathVariable String id) {
        return serverService.findById(id)
            .map(server -> {
                serverService.resetServerDatabase(server);
                log.info("Server {} reset to provisioning state by admin", server.getServerName());
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Server reset to provisioning state. The provisioning system will reinitialize the database."
                ));
            })
            .orElse(ResponseEntity.status(404).body(Map.of("success", false, "error", "Server not found")));
    }

    @PostMapping("/{id}/export-data")
    public ResponseEntity<?> exportData(@PathVariable String id) {
        return serverService.findById(id)
            .map(server -> ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Data export initiated. You will receive an email with the download link."
            )))
            .orElse(ResponseEntity.status(404).body(Map.of("success", false, "error", "Server not found")));
    }

    @PostMapping("/search")
    public ResponseEntity<?> searchServers(@RequestBody @Valid SearchRequest request) {
        String query = request.query() != null ? request.query() : "";
        Map<String, Object> filters = request.filters() != null ? request.filters() : Map.of();

        String plan = filters.get("plan") != null ? filters.get("plan").toString() : null;
        String status = filters.get("status") != null ? filters.get("status").toString() : null;

        List<Server> servers = serverService.findServers(query, plan, status, "createdAt", "desc", 0, 50);
        long total = serverService.countServers(query, plan, status);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of(
                "servers", servers,
                "total", total
            )
        ));
    }

    @PostMapping("/export")
    public ResponseEntity<?> exportServers(@RequestBody @Valid ExportRequest request) {
        String format = request.format() != null ? request.format() : "json";
        Map<String, Object> filters = request.filters() != null ? request.filters() : Map.of();

        String plan = filters.get("plan") != null ? filters.get("plan").toString() : null;
        String status = filters.get("status") != null ? filters.get("status").toString() : null;

        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                .header("Content-Type", "text/csv")
                .header("Content-Disposition", "attachment; filename=servers-export.csv")
                .body(serverService.exportServersCsv(plan, status));
        }

        List<Server> servers = serverService.findServers(null, plan, status, "createdAt", "desc", 0, 10000);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of(
                "servers", servers,
                "exportedAt", new Date(),
                "format", format,
                "count", servers.size()
            )
        ));
    }

    public record SearchRequest(
        @Size(max = RequestValidationLimits.ADMIN_SEARCH_QUERY_MAX_LENGTH) String query,
        Map<String, Object> filters
    ) {}

    public record ExportRequest(
        @Size(max = RequestValidationLimits.EXPORT_FORMAT_MAX_LENGTH) String format,
        Map<String, Object> filters
    ) {}

    public record UsageBatchRequest(
        @NotEmpty @Size(max = RequestValidationLimits.ADMIN_BULK_SERVER_IDS_MAX_ENTRIES) List<@NotBlank String> serverIds,
        Boolean forceRefresh
    ) {}

    public record CreateServerRequest(
        @NotBlank @Size(max = RequestValidationLimits.ADMIN_SERVER_NAME_MAX_LENGTH) String serverName,
        @NotBlank @Size(max = RequestValidationLimits.DOMAIN_MAX_LENGTH) String customDomain,
        @Email @NotBlank @Size(max = RequestValidationLimits.EMAIL_MAX_LENGTH) String adminEmail,
        @Size(max = RequestValidationLimits.ADMIN_PLAN_MAX_LENGTH) String plan
    ) {}

    public record UpdateStatsRequest(
        @Min(0) Integer userCount,
        @Min(0) Integer ticketCount,
        Date lastActivityAt
    ) {}

    public record BulkOperationRequest(
        @NotBlank @Size(max = RequestValidationLimits.ADMIN_BULK_ACTION_MAX_LENGTH) String action,
        @NotEmpty @Size(max = RequestValidationLimits.ADMIN_BULK_SERVER_IDS_MAX_ENTRIES) List<@NotBlank String> serverIds,
        Map<String, Object> parameters
    ) {}
}
