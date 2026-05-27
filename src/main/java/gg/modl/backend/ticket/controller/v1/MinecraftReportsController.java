package gg.modl.backend.ticket.controller.v1;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.dto.request.AssignReportRequest;
import gg.modl.backend.ticket.dto.request.DismissReportRequest;
import gg.modl.backend.ticket.dto.request.ResolveReportRequest;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.ticket.service.MinecraftTicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_REPORTS)
@RequiredArgsConstructor
@Validated
public class MinecraftReportsController {
    private final MinecraftTicketService minecraftTicketService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllReports(
        @RequestParam(defaultValue = "open") String status,
        @RequestParam(defaultValue = "50") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        List<Map<String, Object>> reports = minecraftTicketService.getMinecraftReports(server, status, limit);
        return ResponseEntity.ok(Map.of(
            "status", 200,
            "reports", reports
        ));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismissReport(
        @PathVariable String id,
        @RequestBody @Valid DismissReportRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftTicketService.ReportOperationResult result = minecraftTicketService.dismissMinecraftReport(server, id, request);
        if (result.status() == MinecraftTicketService.ReportOperationStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", "Report not found"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "success", true,
            "message", "Report dismissed"
        ));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveReport(
        @PathVariable String id,
        @RequestBody @Valid ResolveReportRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftTicketService.ReportOperationResult result = minecraftTicketService.resolveMinecraftReport(server, id, request);
        if (result.status() == MinecraftTicketService.ReportOperationStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", "Report not found"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "success", true,
            "message", "Report resolved"
        ));
    }

    @GetMapping("/player/{uuid}")
    public ResponseEntity<Map<String, Object>> getPlayerReports(
        @PathVariable String uuid,
        @RequestParam(defaultValue = "all") String status,
        @RequestParam(defaultValue = "50") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        List<Map<String, Object>> reports = minecraftTicketService.getMinecraftReportsForPlayer(server, uuid, status, limit);
        return ResponseEntity.ok(Map.of(
            "status", 200,
            "reports", reports
        ));
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<Map<String, Object>> assignReport(
        @PathVariable String id,
        @RequestBody @Valid AssignReportRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftTicketService.ReportOperationResult result = minecraftTicketService.assignMinecraftReport(server, id, request);
        if (result.status() == MinecraftTicketService.ReportOperationStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", "Report not found"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "success", true,
            "message", "Report assigned"
        ));
    }
}
