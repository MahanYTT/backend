package gg.modl.backend.player.controller.v3;

import com.google.protobuf.Empty;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.player.dto.response.PunishmentPreviewView;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.player.service.PunishmentQueryService;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.AddPunishmentEvidenceRequest;
import gg.modl.proto.modl.v1.AddPunishmentEvidenceResponse;
import gg.modl.proto.modl.v1.AddPunishmentNoteRequest;
import gg.modl.proto.modl.v1.AddPunishmentNoteResponse;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.ChangePunishmentDurationRequest;
import gg.modl.proto.modl.v1.ChangePunishmentDurationResponse;
import gg.modl.proto.modl.v1.CreateEvidenceUploadTokenRequest;
import gg.modl.proto.modl.v1.CreatePunishmentRequest;
import gg.modl.proto.modl.v1.EvidenceUploadTokenResponse;
import gg.modl.proto.modl.v1.ModifyPunishmentTicketsRequest;
import gg.modl.proto.modl.v1.ModifyPunishmentTicketsResponse;
import gg.modl.proto.modl.v1.PardonPunishmentRequest;
import gg.modl.proto.modl.v1.PardonResponse;
import gg.modl.proto.modl.v1.PunishmentAcknowledgeRequest;
import gg.modl.proto.modl.v1.PunishmentAcknowledgeResponse;
import gg.modl.proto.modl.v1.PunishmentCreateResponse;
import gg.modl.proto.modl.v1.PunishmentDetailResponse;
import gg.modl.proto.modl.v1.PunishmentPreviewResponse;
import gg.modl.proto.modl.v1.RecentPunishmentsResponse;
import gg.modl.proto.modl.v1.StatWipeAcknowledgeRequest;
import gg.modl.proto.modl.v1.StatWipeAcknowledgeResponse;
import gg.modl.proto.modl.v1.TogglePunishmentOptionRequest;
import gg.modl.proto.modl.v1.TogglePunishmentOptionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/punishments")
@RequiredArgsConstructor
@Validated
public class MinecraftPunishmentV3Controller {
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final PunishmentMutationService punishmentMutationService;
    private final PunishmentEvidenceService punishmentEvidenceService;
    private final PunishmentQueryService punishmentQueryService;

    @GetMapping(value = "/preview", produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)
    public ResponseEntity<PunishmentPreviewResponse> previewPunishment(
        @RequestParam String playerUuid,
        @RequestParam int typeOrdinal,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentPreviewView preview = punishmentQueryService.previewPunishment(server, playerUuid, typeOrdinal);

        return ResponseEntity.ok(MinecraftPunishmentV3ProtoMapper.toProto(preview));
    }

    @GetMapping(value = "/recent", produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)
    public ResponseEntity<RecentPunishmentsResponse> getRecentPunishments(
        @RequestParam(defaultValue = "48") @Min(1) @Max(8760) int hours,
        HttpServletRequest httpRequest
    ) {
        validateRecentHours(hours);
        Server server = RequestUtil.getRequestServer(httpRequest);
        RecentPunishmentsResponse.Builder response = RecentPunishmentsResponse.newBuilder()
            .setStatus(200);
        punishmentQueryService.getRecentPunishments(server, hours).stream()
            .map(MinecraftPunishmentV3ProtoMapper::toRecentPunishment)
            .forEach(response::addPunishments);

        return ResponseEntity.ok(response.build());
    }

    @GetMapping(value = "/{punishmentId}", produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)
    public ResponseEntity<?> getPunishmentById(
        @PathVariable String punishmentId,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> punishment = punishmentQueryService
            .getMinecraftPunishmentById(server, punishmentId)
            .orElse(null);

        if (punishment == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .body(ApiError.newBuilder()
                    .setStatusCode(404)
                    .setCode("NOT_FOUND")
                    .setMessage("Punishment not found")
                    .build());
        }

        return ResponseEntity.ok(PunishmentDetailResponse.newBuilder()
            .setStatus(200)
            .setPunishment(MinecraftPunishmentV3ProtoMapper.toPunishmentDetail(punishment))
            .build());
    }

    @PostMapping(
        value = "/dynamic",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PunishmentCreateResponse> createPunishmentDynamic(
        @RequestBody @Valid CreatePunishmentRequest request,
        HttpServletRequest httpRequest
    ) {
        validateCreatePunishmentData(request);
        Server server = RequestUtil.getRequestServer(httpRequest);
        String punishmentId = punishmentLifecycleService.createMinecraftPunishment(
            server,
            MinecraftPunishmentV3ProtoMapper.toLegacyCreatePunishmentRequest(request)
        );

        return ResponseEntity.ok(PunishmentCreateResponse.newBuilder()
            .setStatus(200)
            .setMessage("Punishment created")
            .setPunishmentId(punishmentId)
            .build());
    }

    @PostMapping(
        value = "/create",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<Empty> createPunishment(
        @RequestBody @Valid CreatePunishmentRequest request,
        HttpServletRequest httpRequest
    ) {
        validateCreatePunishmentData(request);
        Server server = RequestUtil.getRequestServer(httpRequest);
        punishmentLifecycleService.createMinecraftPunishment(server, MinecraftPunishmentV3ProtoMapper.toLegacyCreatePunishmentRequest(request));
        return ResponseEntity.ok(Empty.getDefaultInstance());
    }

    @PostMapping(
        value = "/acknowledge",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PunishmentAcknowledgeResponse> acknowledgePunishment(
        @RequestBody @Valid PunishmentAcknowledgeRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentLifecycleService.acknowledgePunishment(
            server,
            UUID.fromString(request.getPlayerUuid()),
            request.getPunishmentId()
        );

        return switch (result.status()) {
            case NOT_FOUND -> acknowledgementResponse(HttpStatus.NOT_FOUND, 404, result.message());
            case INVALID_REQUEST -> acknowledgementResponse(HttpStatus.BAD_REQUEST, 400, result.message());
            case NO_OP, SUCCESS -> acknowledgementResponse(HttpStatus.OK, 200, result.message());
        };
    }

    @PostMapping(
        value = "/{punishmentId}/stat-wipe-acknowledge",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<StatWipeAcknowledgeResponse> acknowledgeStatWipe(
        @PathVariable String punishmentId,
        @RequestBody @Valid StatWipeAcknowledgeRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentMutationService.acknowledgeStatWipe(server, punishmentId);

        return switch (result.status()) {
            case NOT_FOUND -> statWipeResponse(HttpStatus.NOT_FOUND, 404, false, result.message());
            case INVALID_REQUEST -> statWipeResponse(HttpStatus.BAD_REQUEST, 400, false, result.message());
            case NO_OP -> statWipeResponse(HttpStatus.OK, 200, false, result.message());
            case SUCCESS -> statWipeResponse(HttpStatus.OK, 200, true, result.message());
        };
    }

    @PostMapping(
        value = "/{punishmentId}/duration",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<ChangePunishmentDurationResponse> changeDuration(
        @PathVariable String punishmentId,
        @RequestBody @Valid ChangePunishmentDurationRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentMutationService.changeDuration(
            server,
            punishmentId,
            request.hasNewDuration() ? request.getNewDuration() : null,
            emptyToNull(request.getIssuerName()),
            emptyToNull(request.getIssuerId())
        );

        return switch (result.status()) {
            case NOT_FOUND -> changeDurationResponse(HttpStatus.NOT_FOUND, 404, null, result.message());
            case INVALID_REQUEST -> changeDurationResponse(HttpStatus.BAD_REQUEST, 400, null, result.message());
            case NO_OP, SUCCESS -> changeDurationResponse(HttpStatus.OK, 200, result.success(), result.message());
        };
    }

    @PostMapping(
        value = "/{punishmentId}/toggle",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<TogglePunishmentOptionResponse> toggleOption(
        @PathVariable String punishmentId,
        @RequestBody @Valid TogglePunishmentOptionRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentMutationService.toggleOption(
            server,
            punishmentId,
            request.getOption(),
            request.getEnabled(),
            emptyToNull(request.getIssuerName()),
            emptyToNull(request.getIssuerId())
        );

        return switch (result.status()) {
            case NOT_FOUND -> toggleOptionResponse(HttpStatus.NOT_FOUND, 404, null, result.message());
            case INVALID_REQUEST -> toggleOptionResponse(HttpStatus.BAD_REQUEST, 400, null, result.message());
            case NO_OP, SUCCESS -> toggleOptionResponse(HttpStatus.OK, 200, result.success(), result.message());
        };
    }

    @PostMapping(
        value = "/{punishmentId}/note",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<AddPunishmentNoteResponse> addNote(
        @PathVariable String punishmentId,
        @RequestBody @Valid AddPunishmentNoteRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentEvidenceService.addPunishmentNote(
            server,
            punishmentId,
            request.getNote(),
            emptyToNull(request.getIssuerName()),
            emptyToNull(request.getIssuerId())
        );

        if (result.status() == PunishmentOperationStatus.NOT_FOUND) {
            return addNoteResponse(HttpStatus.NOT_FOUND, 404, null, result.message());
        }

        return addNoteResponse(HttpStatus.OK, 200, true, result.message());
    }

    @PostMapping(
        value = "/{punishmentId}/evidence",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<AddPunishmentEvidenceResponse> addEvidence(
        @PathVariable String punishmentId,
        @RequestBody @Valid AddPunishmentEvidenceRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentEvidenceService.addEvidence(
            server,
            punishmentId,
            request.getEvidenceUrl(),
            emptyToNull(request.getIssuerName()),
            emptyToNull(request.getIssuerId())
        );

        if (result.status() == PunishmentOperationStatus.NOT_FOUND) {
            return addEvidenceResponse(HttpStatus.NOT_FOUND, 404, null, result.message());
        }

        return addEvidenceResponse(HttpStatus.OK, 200, true, result.message());
    }

    @PostMapping(
        value = "/{punishmentId}/upload-token",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<EvidenceUploadTokenResponse> createUploadToken(
        @PathVariable String punishmentId,
        @RequestBody(required = false) @Valid CreateEvidenceUploadTokenRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        CreateEvidenceUploadTokenRequest effectiveRequest = request != null
            ? request
            : CreateEvidenceUploadTokenRequest.getDefaultInstance();
        String token = punishmentQueryService.createEvidenceUploadToken(
            server,
            punishmentId,
            emptyToNull(effectiveRequest.getIssuerName())
        ).orElse(null);

        if (token == null) {
            return uploadTokenResponse(HttpStatus.NOT_FOUND, 404, null);
        }

        return uploadTokenResponse(HttpStatus.OK, 200, token);
    }

    @PostMapping(
        value = "/{punishmentId}/pardon",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PardonResponse> pardonPunishment(
        @PathVariable String punishmentId,
        @RequestBody @Valid PardonPunishmentRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentLifecycleService.pardonPunishment(
            server,
            punishmentId,
            emptyToNull(request.getIssuerName()),
            emptyToNull(request.getIssuerId()),
            request.hasReason() ? request.getReason() : null
        );

        return switch (result.status()) {
            case NOT_FOUND -> pardonResponse(HttpStatus.NOT_FOUND, 404, false, 0, result.message());
            case INVALID_REQUEST -> pardonResponse(HttpStatus.BAD_REQUEST, 400, false, 0, result.message());
            case NO_OP -> pardonResponse(HttpStatus.OK, 200, false, 0, result.message());
            case SUCCESS -> pardonResponse(HttpStatus.OK, 200, true, 1, result.message());
        };
    }

    @PostMapping(
        value = "/{punishmentId}/tickets",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<ModifyPunishmentTicketsResponse> modifyTickets(
        @PathVariable String punishmentId,
        @RequestBody @Valid ModifyPunishmentTicketsRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        PunishmentOperationResult result = punishmentMutationService.modifyPunishmentTickets(
            server,
            punishmentId,
            request.getAddTicketIdsList(),
            request.getRemoveTicketIdsList(),
            request.getModifyAssociatedTickets(),
            emptyToNull(request.getIssuerName()),
            emptyToNull(request.getIssuerId())
        );

        if (result.status() == PunishmentOperationStatus.NOT_FOUND) {
            return modifyTicketsResponse(HttpStatus.NOT_FOUND, 404, null, result.message());
        }

        return modifyTicketsResponse(HttpStatus.OK, 200, true, result.message());
    }

    private ResponseEntity<PunishmentAcknowledgeResponse> acknowledgementResponse(
        HttpStatus httpStatus,
        int bodyStatus,
        String message
    ) {
        return ResponseEntity.status(httpStatus)
            .body(PunishmentAcknowledgeResponse.newBuilder()
                .setStatus(bodyStatus)
                .setMessage(message)
                .build());
    }

    private ResponseEntity<TogglePunishmentOptionResponse> toggleOptionResponse(
        HttpStatus httpStatus,
        int bodyStatus,
        Boolean success,
        String message
    ) {
        TogglePunishmentOptionResponse.Builder builder = TogglePunishmentOptionResponse.newBuilder()
            .setStatus(bodyStatus)
            .setMessage(message);
        if (success != null) {
            builder.setSuccess(success);
        }

        return ResponseEntity.status(httpStatus)
            .body(builder.build());
    }

    private ResponseEntity<ChangePunishmentDurationResponse> changeDurationResponse(
        HttpStatus httpStatus,
        int bodyStatus,
        Boolean success,
        String message
    ) {
        ChangePunishmentDurationResponse.Builder builder = ChangePunishmentDurationResponse.newBuilder()
            .setStatus(bodyStatus)
            .setMessage(message);
        if (success != null) {
            builder.setSuccess(success);
        }

        return ResponseEntity.status(httpStatus)
            .body(builder.build());
    }

    private ResponseEntity<AddPunishmentNoteResponse> addNoteResponse(
        HttpStatus httpStatus,
        int bodyStatus,
        Boolean success,
        String message
    ) {
        AddPunishmentNoteResponse.Builder builder = AddPunishmentNoteResponse.newBuilder()
            .setStatus(bodyStatus)
            .setMessage(message);
        if (success != null) {
            builder.setSuccess(success);
        }

        return ResponseEntity.status(httpStatus)
            .body(builder.build());
    }

    private ResponseEntity<AddPunishmentEvidenceResponse> addEvidenceResponse(
        HttpStatus httpStatus,
        int bodyStatus,
        Boolean success,
        String message
    ) {
        AddPunishmentEvidenceResponse.Builder builder = AddPunishmentEvidenceResponse.newBuilder()
            .setStatus(bodyStatus)
            .setMessage(message);
        if (success != null) {
            builder.setSuccess(success);
        }

        return ResponseEntity.status(httpStatus)
            .body(builder.build());
    }

    private ResponseEntity<EvidenceUploadTokenResponse> uploadTokenResponse(
        HttpStatus httpStatus,
        int bodyStatus,
        String token
    ) {
        EvidenceUploadTokenResponse.Builder builder = EvidenceUploadTokenResponse.newBuilder()
            .setStatus(bodyStatus);
        if (token != null) {
            builder.setToken(token);
        }

        return ResponseEntity.status(httpStatus)
            .body(builder.build());
    }

    private ResponseEntity<PardonResponse> pardonResponse(
        HttpStatus httpStatus,
        int bodyStatus,
        boolean success,
        int pardonedCount,
        String message
    ) {
        return ResponseEntity.status(httpStatus)
            .body(PardonResponse.newBuilder()
                .setStatus(bodyStatus)
                .setSuccess(success)
                .setPardonedCount(pardonedCount)
                .setMessage(message)
                .build());
    }

    private ResponseEntity<ModifyPunishmentTicketsResponse> modifyTicketsResponse(
        HttpStatus httpStatus,
        int bodyStatus,
        Boolean success,
        String message
    ) {
        ModifyPunishmentTicketsResponse.Builder builder = ModifyPunishmentTicketsResponse.newBuilder()
            .setStatus(bodyStatus)
            .setMessage(message);
        if (success != null) {
            builder.setSuccess(success);
        }

        return ResponseEntity.status(httpStatus)
            .body(builder.build());
    }

    private void validateCreatePunishmentData(CreatePunishmentRequest request) {
        if (request.hasData()
            && request.getData().getFieldsCount() > RequestValidationLimits.PLAYER_PUNISHMENT_DATA_MAX_ENTRIES) {
            throw new ValidationException("data must contain no more than "
                + RequestValidationLimits.PLAYER_PUNISHMENT_DATA_MAX_ENTRIES + " entries");
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private ResponseEntity<StatWipeAcknowledgeResponse> statWipeResponse(
        HttpStatus httpStatus,
        int bodyStatus,
        boolean success,
        String message
    ) {
        return ResponseEntity.status(httpStatus)
            .body(StatWipeAcknowledgeResponse.newBuilder()
                .setStatus(bodyStatus)
                .setSuccess(success)
                .setMessage(message)
                .build());
    }

    private void validateRecentHours(int hours) {
        if (hours < 1 || hours > 8760) {
            throw new ValidationException("hours must be between 1 and 8760");
        }
    }
}
