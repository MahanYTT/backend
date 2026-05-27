package gg.modl.backend.ticket.controller.v3;

import gg.modl.backend.ai.service.AITicketAnalysisService;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.service.MinecraftTicketService;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.ClaimTicketResponse;
import gg.modl.proto.modl.v1.FieldViolation;
import gg.modl.proto.modl.v1.MinecraftClaimTicketRequest;
import gg.modl.proto.modl.v1.MinecraftCreateTicketRequest;
import gg.modl.proto.modl.v1.MinecraftCreateTicketResponse;
import gg.modl.proto.modl.v1.MinecraftTicketDetailResponse;
import gg.modl.proto.modl.v1.MinecraftTicketsByIdsRequest;
import gg.modl.proto.modl.v1.TicketsResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/tickets")
@RequiredArgsConstructor
public class MinecraftTicketsV3Controller {
    private final MinecraftTicketService minecraftTicketService;
    private final AITicketAnalysisService aiTicketAnalysisService;

    @PostMapping(
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<?> createTicket(
        @RequestBody @Valid MinecraftCreateTicketRequest request,
        HttpServletRequest httpRequest
    ) {
        List<FieldViolation> violations = validateCreateTicketRequest(request);
        if (!violations.isEmpty()) {
            return protobufValidationError(violations);
        }

        Server server = RequestUtil.getRequestServer(httpRequest);
        Ticket ticket = minecraftTicketService.createMinecraftTicket(
            server,
            MinecraftTicketProtoMapper.toCreateTicketRequest(request)
        );

        if (TicketCategory.fromCanonicalId(request.getType()) == TicketCategory.CHAT
            && request.getChatMessagesCount() > 0) {
            aiTicketAnalysisService.analyzeTicketAsync(server, ticket.getId());
        }

        return ResponseEntity.ok(MinecraftTicketProtoMapper.toCreateTicketResponse(
            200,
            true,
            ticket.getId(),
            "Ticket created successfully"
        ));
    }

    @PostMapping(
        value = "/unfinished",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<?> createUnfinishedTicket(
        @RequestBody @Valid MinecraftCreateTicketRequest request,
        HttpServletRequest httpRequest
    ) {
        List<FieldViolation> violations = validateCreateTicketRequest(request);
        if (!violations.isEmpty()) {
            return protobufValidationError(violations);
        }

        Server server = RequestUtil.getRequestServer(httpRequest);
        Ticket ticket = minecraftTicketService.createUnfinishedMinecraftTicket(
            server,
            MinecraftTicketProtoMapper.toCreateTicketRequest(request)
        );

        return ResponseEntity.ok(MinecraftTicketProtoMapper.toCreateTicketResponse(
            200,
            true,
            ticket.getId(),
            "Ticket draft created - complete the form on the panel"
        ));
    }

    @GetMapping(produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)
    public ResponseEntity<?> getAllTickets(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String type,
        @RequestParam(defaultValue = "50") int limit,
        HttpServletRequest httpRequest
    ) {
        if (limit < RequestValidationLimits.PAGINATION_LIMIT_MIN || limit > RequestValidationLimits.PAGINATION_LIMIT_MAX) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid data provided.");
        }

        Server server = RequestUtil.getRequestServer(httpRequest);
        List<Map<String, Object>> tickets = minecraftTicketService.getMinecraftTickets(server, status, type, limit)
            .stream()
            .map(minecraftTicketService::toTicketListItem)
            .toList();

        return ResponseEntity.ok(MinecraftTicketProtoMapper.toTicketsResponse(200, tickets));
    }

    @GetMapping(
        value = "/player/{uuid}",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<TicketsResponse> getPlayerTickets(
        @PathVariable String uuid,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        List<Map<String, Object>> tickets = minecraftTicketService.getMinecraftTicketsByCreator(server, uuid, 50)
            .stream()
            .map(MinecraftTicketsV3Controller::toLegacyPlayerTicketItem)
            .toList();

        return ResponseEntity.ok(MinecraftTicketProtoMapper.toTicketsResponse(200, tickets));
    }

    @GetMapping(
        value = "/{id}",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<?> getTicket(
        @PathVariable String id,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Ticket ticket = minecraftTicketService.getMinecraftTicket(server, id).orElse(null);
        if (ticket == null) {
            return protobufError(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ticket not found");
        }

        return ResponseEntity.ok(MinecraftTicketProtoMapper.toTicketDetailResponse(
            200,
            minecraftTicketService.toTicketDetail(ticket)
        ));
    }

    @PostMapping(
        value = "/{id}/claim",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<?> claimTicket(
        @PathVariable String id,
        @RequestBody @Valid MinecraftClaimTicketRequest request,
        HttpServletRequest httpRequest
    ) {
        List<FieldViolation> violations = validateClaimTicketRequest(request);
        if (!violations.isEmpty()) {
            return protobufValidationError(violations);
        }

        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftTicketService.MinecraftTicketClaimResult result = minecraftTicketService.claimMinecraftTicket(
            server,
            id,
            MinecraftTicketProtoMapper.toClaimTicketRequest(request)
        );

        return switch (result.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(MinecraftTicketProtoMapper.toClaimTicketResponse(
                404,
                false,
                "Ticket not found",
                null,
                null
            ));
            case ALREADY_LINKED -> ResponseEntity.status(HttpStatus.CONFLICT).body(MinecraftTicketProtoMapper.toClaimTicketResponse(
                409,
                false,
                "Ticket is already linked to a Minecraft account",
                null,
                null
            ));
            case SUCCESS -> ResponseEntity.ok(MinecraftTicketProtoMapper.toClaimTicketResponse(
                200,
                true,
                "Ticket successfully linked to your account",
                id,
                result.ticket() != null ? result.ticket().getSubject() : null
            ));
        };
    }

    @PostMapping(
        value = "/by-ids",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<?> getTicketsByIds(
        @RequestBody(required = false) @Valid MinecraftTicketsByIdsRequest request,
        HttpServletRequest httpRequest
    ) {
        MinecraftTicketsByIdsRequest effectiveRequest = request != null
            ? request
            : MinecraftTicketsByIdsRequest.getDefaultInstance();
        if (effectiveRequest.getIdsCount() == 0) {
            return ResponseEntity.ok(MinecraftTicketProtoMapper.toTicketsResponse(200, List.of()));
        }
        if (!isValidTicketIdsRequest(effectiveRequest)) {
            return protobufError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid data provided.");
        }

        Server server = RequestUtil.getRequestServer(httpRequest);
        List<Map<String, Object>> tickets = minecraftTicketService.getMinecraftTicketsByIds(server, effectiveRequest.getIdsList())
            .stream()
            .map(minecraftTicketService::toTicketLookupItem)
            .toList();

        return ResponseEntity.ok(MinecraftTicketProtoMapper.toTicketsResponse(200, tickets));
    }

    private static Map<String, Object> toLegacyPlayerTicketItem(Ticket ticket) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", ticket.getId());
        response.put("type", ticket.getType() != null ? ticket.getType().getId() : null);
        response.put("category", ticket.getType() != null ? ticket.getType().getId() : null);
        response.put("subject", ticket.getSubject());
        response.put("status", ticket.getStatus() != null ? ticket.getStatus().getId() : null);
        response.put("createdAt", ticket.getCreated());
        return response;
    }

    private static boolean isValidTicketIdsRequest(MinecraftTicketsByIdsRequest request) {
        if (request.getIdsCount() > RequestValidationLimits.TICKET_IDS_MAX_ENTRIES) {
            return false;
        }
        return request.getIdsList().stream()
            .allMatch(id -> id != null && !id.isBlank() && id.length() <= RequestValidationLimits.ID_MAX_LENGTH);
    }

    private static List<FieldViolation> validateCreateTicketRequest(MinecraftCreateTicketRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        if (isBlank(request.getCreatorUuid()) || !request.getCreatorUuid().matches(RegExpConstants.UUID)) {
            violations.add(fieldViolation("creator_uuid", "creator_uuid must be a valid UUID"));
        }
        if (request.hasCreatorName() && !request.getCreatorName().matches(RegExpConstants.MINECRAFT_USERNAME)) {
            violations.add(fieldViolation("creator_name", "creator_name must be a valid Minecraft username"));
        }
        if (isBlank(request.getType()) || !isKnownTicketType(request.getType())) {
            violations.add(fieldViolation("type", "type must be a known ticket category"));
        }
        if (request.hasSubject() && request.getSubject().length() > RequestValidationLimits.TICKET_SUBJECT_MAX_LENGTH) {
            violations.add(fieldViolation("subject", "subject is too long"));
        }
        if (request.hasDescription() && request.getDescription().length() > RequestValidationLimits.TICKET_DESCRIPTION_MAX_LENGTH) {
            violations.add(fieldViolation("description", "description is too long"));
        }
        if (request.hasReportedPlayerUuid() && !request.getReportedPlayerUuid().matches(RegExpConstants.UUID)) {
            violations.add(fieldViolation("reported_player_uuid", "reported_player_uuid must be a valid UUID"));
        }
        if (request.hasReportedPlayerName()
            && request.getReportedPlayerName().length() > RequestValidationLimits.LOG_USERNAME_MAX_LENGTH) {
            violations.add(fieldViolation("reported_player_name", "reported_player_name is too long"));
        }
        if (request.getChatMessagesCount() > RequestValidationLimits.MC_TICKET_CHAT_MESSAGES_MAX_ENTRIES) {
            violations.add(fieldViolation("chat_messages", "too many chat messages"));
        }
        for (int i = 0; i < request.getChatMessagesCount(); i++) {
            if (request.getChatMessages(i).length() > RequestValidationLimits.CHAT_LOG_MESSAGE_MAX_LENGTH) {
                violations.add(fieldViolation("chat_messages[" + i + "]", "chat message is too long"));
            }
        }
        if (request.getTagsCount() > RequestValidationLimits.TICKET_TAGS_MAX_ENTRIES) {
            violations.add(fieldViolation("tags", "too many tags"));
        }
        for (int i = 0; i < request.getTagsCount(); i++) {
            String tag = request.getTags(i);
            if (tag == null || tag.isBlank() || tag.length() > RequestValidationLimits.TICKET_TAG_MAX_LENGTH) {
                violations.add(fieldViolation("tags[" + i + "]", "tag is invalid"));
            }
        }
        if (request.hasCreatedServer()
            && request.getCreatedServer().length() > RequestValidationLimits.MC_CREATE_TICKET_SERVER_MAX_LENGTH) {
            violations.add(fieldViolation("created_server", "created_server is too long"));
        }
        if (request.hasReplayUrl()
            && request.getReplayUrl().length() > RequestValidationLimits.MC_CREATE_TICKET_REPLAY_URL_MAX_LENGTH) {
            violations.add(fieldViolation("replay_url", "replay_url is too long"));
        }
        return violations;
    }

    private static List<FieldViolation> validateClaimTicketRequest(MinecraftClaimTicketRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        if (isBlank(request.getPlayerUuid()) || !request.getPlayerUuid().matches(RegExpConstants.UUID)) {
            violations.add(fieldViolation("player_uuid", "player_uuid must be a valid UUID"));
        }
        if (isBlank(request.getPlayerName()) || !request.getPlayerName().matches(RegExpConstants.MINECRAFT_USERNAME)) {
            violations.add(fieldViolation("player_name", "player_name must be a valid Minecraft username"));
        }
        return violations;
    }

    private static boolean isKnownTicketType(String type) {
        try {
            TicketCategory.fromCanonicalId(type);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private static ResponseEntity<ApiError> protobufError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .body(ApiError.newBuilder()
                .setStatusCode(status.value())
                .setCode(code)
                .setMessage(message)
                .build());
    }
}
