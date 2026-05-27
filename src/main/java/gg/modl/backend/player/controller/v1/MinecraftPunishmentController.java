package gg.modl.backend.player.controller.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import gg.modl.backend.player.dto.request.CreateUploadTokenRequest;
import gg.modl.backend.player.dto.request.ModifyPunishmentTicketsRequest;
import gg.modl.backend.player.dto.response.PunishmentPreviewView;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.player.service.PunishmentQueryService;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@RequestMapping(RESTMappingV1.MINECRAFT_PUNISHMENTS)
@RequiredArgsConstructor
@Validated
public class MinecraftPunishmentController {
    private final PunishmentQueryService punishmentQueryService;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final PunishmentEvidenceService punishmentEvidenceService;
    private final PunishmentMutationService punishmentMutationService;

    @PostMapping("/create")
    public ResponseEntity<?> createPunishment(
        @RequestBody @Valid MinecraftCreatePunishmentRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        punishmentLifecycleService.createMinecraftPunishment(server, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/dynamic")
    public ResponseEntity<Map<String, Object>> createPunishmentDynamic(
        @RequestBody @Valid MinecraftCreatePunishmentRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        String punishmentId = punishmentLifecycleService.createMinecraftPunishment(server, request);
        return ResponseEntity.ok(Map.of(
            "status", 200,
            "message", "Punishment created",
            "punishmentId", punishmentId
        ));
    }

    @GetMapping("/{punishmentId}")
    public ResponseEntity<Map<String, Object>> getPunishmentById(
        @PathVariable String punishmentId,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> punishment = punishmentQueryService.getMinecraftPunishmentById(server, punishmentId).orElse(null);
        if (punishment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", "Punishment not found"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "punishment", punishment
        ));
    }

    @PostMapping("/{punishmentId}/upload-token")
    public ResponseEntity<Map<String, Object>> createUploadToken(
        @PathVariable String punishmentId,
        @RequestBody @Valid CreateUploadTokenRequest body,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        String token = punishmentQueryService.createEvidenceUploadToken(server, punishmentId, body.issuerName()).orElse(null);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", "Punishment not found"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "token", token
        ));
    }

    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> getRecentPunishments(
        @RequestParam(defaultValue = "48") @Min(1) @Max(8760) int hours,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return ResponseEntity.ok(Map.of(
            "status", 200,
            "punishments", punishmentQueryService.getRecentPunishments(server, hours)
        ));
    }

    @GetMapping("/preview")
    public ResponseEntity<PunishmentPreviewView> previewPunishment(
        @RequestParam String playerUuid,
        @RequestParam int typeOrdinal,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return ResponseEntity.ok(punishmentQueryService.previewPunishment(server, playerUuid, typeOrdinal));
    }

    @PostMapping("/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgePunishment(
        @RequestBody @Valid AcknowledgeRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentLifecycleService.acknowledgePunishment(
            server,
            UUID.fromString(request.playerUuid()),
            request.punishmentId()
        );

        return switch (result.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", result.message()
            ));
            case INVALID_REQUEST -> ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "message", result.message()
            ));
            case NO_OP, SUCCESS -> ResponseEntity.ok(Map.of(
                "status", 200,
                "message", result.message()
            ));
        };
    }

    @PostMapping("/{punishmentId}/pardon")
    public ResponseEntity<Map<String, Object>> pardonPunishment(
        @PathVariable String punishmentId,
        @RequestBody @Valid PardonRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentLifecycleService.pardonPunishment(
            server,
            punishmentId,
            request.issuerName(),
            request.issuerId(),
            request.reason()
        );

        return switch (result.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", result.message()
            ));
            case INVALID_REQUEST -> ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "message", result.message()
            ));
            case NO_OP -> ResponseEntity.ok(Map.of(
                "status", 200,
                "success", false,
                "pardonedCount", 0,
                "message", result.message()
            ));
            case SUCCESS -> ResponseEntity.ok(Map.of(
                "status", 200,
                "success", true,
                "pardonedCount", 1,
                "message", result.message()
            ));
        };
    }

    @PostMapping("/{punishmentId}/note")
    public ResponseEntity<Map<String, Object>> addNote(
        @PathVariable String punishmentId,
        @RequestBody @Valid AddNoteRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentEvidenceService.addPunishmentNote(
            server,
            punishmentId,
            request.note(),
            request.issuerName(),
            request.issuerId()
        );

        if (result.status() == PunishmentOperationStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", result.message()
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "success", true,
            "message", result.message()
        ));
    }

    @PostMapping("/{punishmentId}/evidence")
    public ResponseEntity<Map<String, Object>> addEvidence(
        @PathVariable String punishmentId,
        @RequestBody @Valid AddEvidenceRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentEvidenceService.addEvidence(
            server,
            punishmentId,
            request.evidenceUrl(),
            request.issuerName(),
            request.issuerId()
        );

        if (result.status() == PunishmentOperationStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", result.message()
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "success", true,
            "message", result.message()
        ));
    }

    @PostMapping("/{punishmentId}/duration")
    public ResponseEntity<Map<String, Object>> changeDuration(
        @PathVariable String punishmentId,
        @RequestBody @Valid ChangeDurationRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentMutationService.changeDuration(
            server,
            punishmentId,
            request.newDuration(),
            request.issuerName(),
            request.issuerId()
        );

        if (result.status() == PunishmentOperationStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", result.message()
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "success", true,
            "message", result.message()
        ));
    }

    @PostMapping("/{punishmentId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleOption(
        @PathVariable String punishmentId,
        @RequestBody @Valid ToggleOptionRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentMutationService.toggleOption(
            server,
            punishmentId,
            request.option(),
            request.enabled(),
            request.issuerName(),
            request.issuerId()
        );

        return switch (result.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", result.message()
            ));
            case INVALID_REQUEST -> ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "message", result.message()
            ));
            case NO_OP, SUCCESS -> ResponseEntity.ok(Map.of(
                "status", 200,
                "success", result.success(),
                "message", result.message()
            ));
        };
    }

    @PostMapping("/{punishmentId}/stat-wipe-acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeStatWipe(
        @PathVariable String punishmentId,
        @RequestBody @Valid StatWipeAcknowledgeRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentMutationService.acknowledgeStatWipe(server, punishmentId);

        return switch (result.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", result.message()
            ));
            case INVALID_REQUEST -> ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "message", result.message()
            ));
            case NO_OP -> ResponseEntity.ok(Map.of(
                "status", 200,
                "message", result.message()
            ));
            case SUCCESS -> ResponseEntity.ok(Map.of(
                "status", 200,
                "success", true,
                "message", result.message()
            ));
        };
    }

    @PostMapping("/{punishmentId}/tickets")
    public ResponseEntity<Map<String, Object>> modifyPunishmentTickets(
        @PathVariable String punishmentId,
        @RequestBody @Valid ModifyTicketsRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentMutationService.modifyPunishmentTickets(
            server,
            punishmentId,
            new ModifyPunishmentTicketsRequest(
                request.addTicketIds(),
                request.removeTicketIds(),
                request.modifyAssociatedTickets(),
                request.issuerName(),
                request.issuerId()
            )
        );

        if (result.status() == PunishmentOperationStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", result.message()
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "success", true,
            "message", result.message()
        ));
    }

    public record MinecraftCreatePunishmentRequest(
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String targetUuid,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId,
        @JsonProperty("type_ordinal") @Min(0) int typeOrdinal,
        @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_REASON_MAX_LENGTH) String reason,
        @Min(0) Long duration,
        @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_DATA_MAX_ENTRIES) Map<String, Object> data,
        @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_NOTES_MAX_ENTRIES) List<@Size(max = RequestValidationLimits.PLAYER_NOTE_TEXT_MAX_LENGTH) String> notes,
        @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_TICKETS_MAX_ENTRIES) List<@Size(max = RequestValidationLimits.ID_MAX_LENGTH) String> attachedTicketIds,
        @Size(max = RequestValidationLimits.PLAYER_SEVERITY_MAX_LENGTH) String severity,
        @Size(max = RequestValidationLimits.PLAYER_STATUS_MAX_LENGTH) String status
    ) {
    }

    public record AcknowledgeRequest(
        @NotBlank @Size(max = RequestValidationLimits.ID_MAX_LENGTH) String punishmentId,
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String playerUuid,
        @Size(max = RequestValidationLimits.TIMESTAMP_MAX_LENGTH) String executedAt,
        boolean success,
        @Size(max = RequestValidationLimits.PLAYER_MODIFICATION_REASON_MAX_LENGTH) String errorMessage
    ) {
    }

    public record PardonRequest(
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId,
        @Size(max = RequestValidationLimits.PLAYER_MODIFICATION_REASON_MAX_LENGTH) String reason,
        @Size(max = RequestValidationLimits.PUNISHMENT_TYPE_NAME_MAX_LENGTH) String expectedType
    ) {
    }

    public record AddNoteRequest(
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId,
        @NotBlank @Size(max = RequestValidationLimits.PLAYER_NOTE_TEXT_MAX_LENGTH) String note
    ) {
    }

    public record AddEvidenceRequest(
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId,
        @NotBlank @Size(max = RequestValidationLimits.EVIDENCE_URL_MAX_LENGTH) String evidenceUrl
    ) {
    }

    public record ChangeDurationRequest(
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId,
        @Min(0) Long newDuration
    ) {
    }

    public record ToggleOptionRequest(
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId,
        @NotBlank @Size(max = RequestValidationLimits.PLAYER_MODIFICATION_TYPE_MAX_LENGTH) String option,
        boolean enabled
    ) {
    }

    public record StatWipeAcknowledgeRequest(
        @NotBlank @Size(max = RequestValidationLimits.ID_MAX_LENGTH) String punishmentId,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String serverName,
        boolean success
    ) {
    }

    public record ModifyTicketsRequest(
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId,
        @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_TICKETS_MAX_ENTRIES) List<@Size(max = RequestValidationLimits.ID_MAX_LENGTH) String> addTicketIds,
        @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_TICKETS_MAX_ENTRIES) List<@Size(max = RequestValidationLimits.ID_MAX_LENGTH) String> removeTicketIds,
        boolean modifyAssociatedTickets
    ) {
    }
}
