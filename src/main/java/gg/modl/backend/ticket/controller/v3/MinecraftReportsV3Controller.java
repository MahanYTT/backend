package gg.modl.backend.ticket.controller.v3;

import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.service.MinecraftTicketService;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.AssignReportRequest;
import gg.modl.proto.modl.v1.DismissReportRequest;
import gg.modl.proto.modl.v1.FieldViolation;
import gg.modl.proto.modl.v1.MinecraftReportOperationResponse;
import gg.modl.proto.modl.v1.ReportsResponse;
import gg.modl.proto.modl.v1.ResolveReportRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/reports")
@RequiredArgsConstructor
@Validated
public class MinecraftReportsV3Controller {
    private final MinecraftTicketService minecraftTicketService;

    @GetMapping(produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)
    public ResponseEntity<ReportsResponse> getAllReports(
        @RequestParam(defaultValue = "open") String status,
        @RequestParam(defaultValue = "50")
        @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN)
        @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX)
        int limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        List<Map<String, Object>> reports = minecraftTicketService.getMinecraftReports(server, status, limit);
        return ResponseEntity.ok(MinecraftTicketProtoMapper.toReportsResponse(200, reports));
    }

    @GetMapping(value = "/player/{uuid}", produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)
    public ResponseEntity<ReportsResponse> getPlayerReports(
        @PathVariable String uuid,
        @RequestParam(defaultValue = "all") String status,
        @RequestParam(defaultValue = "50")
        @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN)
        @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX)
        int limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        List<Map<String, Object>> reports = minecraftTicketService.getMinecraftReportsForPlayer(server, uuid, status, limit);
        return ResponseEntity.ok(MinecraftTicketProtoMapper.toReportsResponse(200, reports));
    }

    @PostMapping(
        value = "/{id}/dismiss",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<?> dismissReport(
        @PathVariable String id,
        @RequestBody @Valid DismissReportRequest request,
        HttpServletRequest httpRequest
    ) {
        List<FieldViolation> violations = new ArrayList<>();
        validateRequiredString(violations, "dismissed_by", request.getDismissedBy(), RequestValidationLimits.REPORT_STAFF_NAME_MAX_LENGTH);
        validateOptionalString(violations, "reason", request.hasReason(), request.getReason(), RequestValidationLimits.REPORT_REASON_MAX_LENGTH);
        if (!violations.isEmpty()) {
            return protobufValidationError(violations);
        }

        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftTicketService.ReportOperationResult result = minecraftTicketService.dismissMinecraftReport(
            server,
            id,
            request.getDismissedBy(),
            optionalString(request.hasReason(), request.getReason())
        );
        return reportOperationResponse(result, "Report dismissed");
    }

    @PostMapping(
        value = "/{id}/resolve",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<?> resolveReport(
        @PathVariable String id,
        @RequestBody @Valid ResolveReportRequest request,
        HttpServletRequest httpRequest
    ) {
        List<FieldViolation> violations = new ArrayList<>();
        validateRequiredString(violations, "resolved_by", request.getResolvedBy(), RequestValidationLimits.REPORT_STAFF_NAME_MAX_LENGTH);
        validateOptionalString(violations, "resolution", true, request.getResolution(), RequestValidationLimits.REPORT_REASON_MAX_LENGTH);
        validateRequiredString(violations, "punishment_id", request.getPunishmentId(), RequestValidationLimits.REPORT_PUNISHMENT_ID_MAX_LENGTH);
        if (!violations.isEmpty()) {
            return protobufValidationError(violations);
        }

        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftTicketService.ReportOperationResult result = minecraftTicketService.resolveMinecraftReport(
            server,
            id,
            request.getResolvedBy(),
            request.getResolution(),
            request.getPunishmentId()
        );
        return reportOperationResponse(result, "Report resolved");
    }

    @PostMapping(
        value = "/{id}/assign",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<?> assignReport(
        @PathVariable String id,
        @RequestBody @Valid AssignReportRequest request,
        HttpServletRequest httpRequest
    ) {
        List<FieldViolation> violations = new ArrayList<>();
        validateRequiredString(violations, "assignee", request.getAssignee(), RequestValidationLimits.REPORT_ASSIGNEE_MAX_LENGTH);
        if (!violations.isEmpty()) {
            return protobufValidationError(violations);
        }

        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftTicketService.ReportOperationResult result = minecraftTicketService.assignMinecraftReport(
            server,
            id,
            request.getAssignee()
        );
        return reportOperationResponse(result, "Report assigned");
    }

    private static ResponseEntity<MinecraftReportOperationResponse> reportOperationResponse(
        MinecraftTicketService.ReportOperationResult result,
        String successMessage
    ) {
        if (result.status() == MinecraftTicketService.ReportOperationStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(MinecraftTicketProtoMapper.toReportOperationResponse(
                404,
                false,
                "Report not found"
            ));
        }

        return ResponseEntity.ok(MinecraftTicketProtoMapper.toReportOperationResponse(200, true, successMessage));
    }

    private static String optionalString(boolean hasValue, String value) {
        return hasValue ? value : null;
    }

    private static void validateRequiredString(List<FieldViolation> violations, String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            violations.add(fieldViolation(field, field + " is required"));
            return;
        }
        if (value.length() > maxLength) {
            violations.add(fieldViolation(field, field + " is too long"));
        }
    }

    private static void validateOptionalString(
        List<FieldViolation> violations,
        String field,
        boolean present,
        String value,
        int maxLength
    ) {
        if (present && value != null && value.length() > maxLength) {
            violations.add(fieldViolation(field, field + " is too long"));
        }
    }

    private static FieldViolation fieldViolation(String field, String message) {
        return FieldViolation.newBuilder()
            .setField(field)
            .setMessage(message)
            .build();
    }

    private static ResponseEntity<ApiError> protobufValidationError(List<FieldViolation> violations) {
        ApiError.Builder error = ApiError.newBuilder()
            .setStatusCode(HttpStatus.BAD_REQUEST.value())
            .setCode("INVALID_ARGUMENT")
            .setMessage("Invalid data provided.");
        violations.forEach(error::addFieldViolations);
        return ResponseEntity.badRequest()
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .body(error.build());
    }
}
