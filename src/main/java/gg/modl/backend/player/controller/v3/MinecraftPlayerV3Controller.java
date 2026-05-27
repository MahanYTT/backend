package gg.modl.backend.player.controller.v3;

import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.player.service.MinecraftPlayerService;
import gg.modl.backend.player.service.PlayerLookupService;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.CreatePlayerNoteRequest;
import gg.modl.proto.modl.v1.LinkedAccountsResponse;
import gg.modl.proto.modl.v1.OnlinePlayersResponse;
import gg.modl.proto.modl.v1.PaginatedNotesResponse;
import gg.modl.proto.modl.v1.PaginatedPunishmentsResponse;
import gg.modl.proto.modl.v1.PardonPlayerRequest;
import gg.modl.proto.modl.v1.PardonResponse;
import gg.modl.proto.modl.v1.PlayerDisconnectRequest;
import gg.modl.proto.modl.v1.PlayerGetResponse;
import gg.modl.proto.modl.v1.PlayerLoginRequest;
import gg.modl.proto.modl.v1.PlayerLoginResponse;
import gg.modl.proto.modl.v1.PlayerLookupRequest;
import gg.modl.proto.modl.v1.PlayerLookupResponse;
import gg.modl.proto.modl.v1.PlayerNameResponse;
import gg.modl.proto.modl.v1.PlayerNoteCreateResponse;
import gg.modl.proto.modl.v1.PlayerProfileResponse;
import gg.modl.proto.modl.v1.ReportsResponse;
import gg.modl.proto.modl.v1.SimpleResponse;
import gg.modl.proto.modl.v1.SubmitPlayerIpInfoRequest;
import gg.modl.proto.modl.v1.UpdatePlayerServerRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.UUID;
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
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/players")
@RequiredArgsConstructor
public class MinecraftPlayerV3Controller {
    private final MinecraftPlayerService minecraftPlayerService;
    private final PlayerLookupService playerLookupService;

    @PostMapping(
        value = "/login",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PlayerLoginResponse> login(
        @RequestBody @Valid PlayerLoginRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> ipInfo = request.hasIpInfo()
            ? MinecraftPlayerProtoMapper.structToMap(request.getIpInfo())
            : null;

        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.login(
            server,
            UUID.fromString(request.getMinecraftUuid()),
            request.getUsername(),
            request.hasIpAddress() ? request.getIpAddress() : null,
            ipInfo,
            request.hasSkinHash() ? request.getSkinHash() : null,
            request.hasServerName() ? request.getServerName() : null
        );

        return ResponseEntity.status(response.status())
            .body(MinecraftPlayerProtoMapper.toPlayerLoginResponse(response.body()));
    }

    @PostMapping(
        value = "/disconnect",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SimpleResponse> disconnect(
        @RequestBody @Valid PlayerDisconnectRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> response = minecraftPlayerService.disconnect(
            server,
            request.getMinecraftUuid(),
            request.getSessionDurationMs()
        );

        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toSimpleResponse(response));
    }

    @PostMapping(
        value = "/update-server",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SimpleResponse> updateServer(
        @RequestBody @Valid UpdatePlayerServerRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> response = minecraftPlayerService.updateServer(
            server,
            request.getMinecraftUuid(),
            request.getServerName()
        );

        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toSimpleResponse(response));
    }

    @PostMapping(
        value = "/submit-ip-info",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SimpleResponse> submitIpInfo(
        @RequestBody @Valid SubmitPlayerIpInfoRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> response = minecraftPlayerService.submitIpInfo(
            server,
            request.getMinecraftUuid(),
            request.getIp(),
            request.getCountry(),
            request.getRegion(),
            request.getAsn(),
            request.getProxy(),
            request.getHosting()
        );

        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toSimpleResponse(response));
    }

    @GetMapping(
        value = "/online",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<OnlinePlayersResponse> getOnlinePlayers(HttpServletRequest httpRequest) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> response = minecraftPlayerService.getOnlinePlayers(server);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toOnlinePlayersResponse(response));
    }

    @GetMapping(
        value = "/{uuid}",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PlayerProfileResponse> getPlayerByUuid(
        @PathVariable String uuid,
        @RequestParam(required = false) Integer punishmentLimit,
        @RequestParam(required = false) Integer noteLimit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.getPlayerByUuid(server, uuid, punishmentLimit, noteLimit);
        requireSuccess(response);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toPlayerProfileResponse(response.body()));
    }

    @GetMapping(
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PlayerGetResponse> getPlayerByQuery(
        @RequestParam(required = false) String minecraftUuid,
        @RequestParam(defaultValue = "true") boolean queryMojang,
        HttpServletRequest httpRequest
    ) {
        requireNotBlank(minecraftUuid, "minecraftUuid parameter required");
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.getPlayerByMinecraftUuid(server, minecraftUuid, queryMojang);
        requireSuccess(response);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toPlayerGetResponse(response.body()));
    }

    @GetMapping(
        value = "/by-name",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PlayerNameResponse> getPlayerByUsername(
        @RequestParam(required = false) String username,
        @RequestParam(defaultValue = "true") boolean queryMojang,
        HttpServletRequest httpRequest
    ) {
        requireNotBlank(username, "username parameter required");
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.getPlayerByUsername(server, username, queryMojang);
        requireSuccess(response);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toPlayerNameResponse(response.body()));
    }

    @PostMapping(
        value = "/lookup",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PlayerLookupResponse> lookupPlayer(
        @RequestBody @Valid PlayerLookupRequest request,
        HttpServletRequest httpRequest
    ) {
        requireNotBlank(request.getQuery(), "query is required");
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.lookupPlayer(
            server,
            request.getQuery(),
            shouldQueryMojang(request)
        );
        requireSuccess(response);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toPlayerLookupResponse(response.body()));
    }

    @PostMapping(
        value = "/lookup-profile",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PlayerProfileResponse> lookupProfile(
        @RequestBody @Valid PlayerLookupRequest request,
        @RequestParam(required = false) Integer punishmentLimit,
        @RequestParam(required = false) Integer noteLimit,
        HttpServletRequest httpRequest
    ) {
        requireNotBlank(request.getQuery(), "query is required");
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.lookupProfile(
            server,
            request.getQuery(),
            shouldQueryMojang(request),
            punishmentLimit,
            noteLimit
        );
        requireSuccess(response);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toPlayerProfileResponse(response.body()));
    }

    @PostMapping(
        value = "/{uuid}/notes",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PlayerNoteCreateResponse> createPlayerNote(
        @PathVariable String uuid,
        @RequestBody @Valid CreatePlayerNoteRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.createNote(
            server,
            uuid,
            request.getText(),
            request.hasIssuerName() ? request.getIssuerName() : null,
            request.hasIssuerId() ? request.getIssuerId() : null
        );
        return ResponseEntity.status(response.status())
            .body(MinecraftPlayerProtoMapper.toPlayerNoteCreateResponse(response.body()));
    }

    @GetMapping(
        value = "/{uuid}/linked-accounts",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<LinkedAccountsResponse> getLinkedAccounts(
        @PathVariable String uuid,
        @RequestParam(required = false) @Min(RequestValidationLimits.PAGINATION_PAGE_MIN) Integer page,
        @RequestParam(required = false) @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) Integer limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.getLinkedAccounts(server, uuid, page, limit);
        requireSuccess(response);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toLinkedAccountsResponse(response.body()));
    }

    @GetMapping(
        value = "/{uuid}/punishments",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PaginatedPunishmentsResponse> getPlayerPunishments(
        @PathVariable String uuid,
        @RequestParam(defaultValue = "1") @Min(RequestValidationLimits.PAGINATION_PAGE_MIN) int page,
        @RequestParam(defaultValue = "7") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.getPlayerPunishments(server, uuid, page, limit);
        requireSuccess(response);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toPaginatedPunishmentsResponse(response.body()));
    }

    @GetMapping(
        value = "/{uuid}/notes",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PaginatedNotesResponse> getPlayerNotes(
        @PathVariable String uuid,
        @RequestParam(defaultValue = "1") @Min(RequestValidationLimits.PAGINATION_PAGE_MIN) int page,
        @RequestParam(defaultValue = "7") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.getPlayerNotes(server, uuid, page, limit);
        requireSuccess(response);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toPaginatedNotesResponse(response.body()));
    }

    @GetMapping(
        value = "/{uuid}/reports",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<ReportsResponse> getPlayerReports(
        @PathVariable String uuid,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> response = minecraftPlayerService.getPlayerReports(server, uuid);
        return ResponseEntity.ok(MinecraftPlayerProtoMapper.toReportsResponse(response));
    }

    @PostMapping(
        value = "/pardon",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<PardonResponse> pardonPlayer(
        @RequestBody @Valid PardonPlayerRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> response = minecraftPlayerService.pardonPlayer(
            server,
            request.getPlayerName(),
            request.hasPunishmentType() ? request.getPunishmentType() : null,
            request.hasIssuerName() ? request.getIssuerName() : null,
            request.hasIssuerId() ? request.getIssuerId() : null,
            request.hasReason() ? request.getReason() : null
        );
        HttpStatus status = intStatus(response) == 404 ? HttpStatus.NOT_FOUND : HttpStatus.OK;
        return ResponseEntity.status(status)
            .body(MinecraftPlayerProtoMapper.toPardonResponse(response));
    }

    private static boolean shouldQueryMojang(PlayerLookupRequest request) {
        return !request.hasQueryMojang() || request.getQueryMojang();
    }

    private static int intStatus(Map<String, Object> response) {
        Object status = response.get("status");
        if (status instanceof Number number) {
            return number.intValue();
        }
        return 200;
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(message);
        }
    }

    private static void requireSuccess(MinecraftPlayerService.ServiceResponse response) {
        if (response.status() == HttpStatus.OK) {
            return;
        }
        Object message = response.body().get("message");
        if (response.status() == HttpStatus.NOT_FOUND) {
            throw new ResourceNotFoundException(message == null ? "Not found" : message.toString());
        }
        if (response.status() == HttpStatus.BAD_REQUEST) {
            throw new ValidationException(message == null ? "Invalid request" : message.toString());
        }
        throw new IllegalStateException(message == null ? "Unexpected player lookup response" : message.toString());
    }
}
