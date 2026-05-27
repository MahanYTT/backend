package gg.modl.backend.player.controller.v1;

import gg.modl.backend.player.service.MinecraftPlayerService;
import gg.modl.backend.player.service.PlayerLookupService;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequestMapping(RESTMappingV1.MINECRAFT_PLAYERS)
@RequiredArgsConstructor
@Slf4j
@Validated
public class MinecraftPlayerController {
    private final MinecraftPlayerService minecraftPlayerService;
    private final PlayerLookupService playerLookupService;

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
        @RequestBody @Valid LoginRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.login(
            server,
            UUID.fromString(request.minecraftUUID()),
            request.username(),
            request.ip(),
            request.ipInfo(),
            request.skinHash(),
            request.serverName()
        );
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(
        @RequestBody @Valid DisconnectRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return ResponseEntity.ok(minecraftPlayerService.disconnect(server, request.minecraftUuid(), request.sessionDurationMs()));
    }

    @PostMapping("/update-server")
    public ResponseEntity<Map<String, Object>> updateServer(
        @RequestBody @Valid UpdateServerRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return ResponseEntity.ok(minecraftPlayerService.updateServer(server, request.minecraftUuid(), request.serverName()));
    }

    @GetMapping("/online")
    public ResponseEntity<Map<String, Object>> getOnlinePlayers(HttpServletRequest httpRequest) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return ResponseEntity.ok(minecraftPlayerService.getOnlinePlayers(server));
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<Map<String, Object>> getPlayerByUuid(
        @PathVariable String uuid,
        @RequestParam(required = false) Integer punishmentLimit,
        @RequestParam(required = false) Integer noteLimit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.getPlayerByUuid(server, uuid, punishmentLimit, noteLimit);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getPlayerByQuery(
        @RequestParam(required = false) String minecraftUuid,
        @RequestParam(defaultValue = "true") boolean queryMojang,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.getPlayerByMinecraftUuid(server, minecraftUuid, queryMojang);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping("/by-name")
    public ResponseEntity<Map<String, Object>> getPlayerByUsername(
        @RequestParam String username,
        @RequestParam(defaultValue = "true") boolean queryMojang,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.getPlayerByUsername(server, username, queryMojang);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @PostMapping("/lookup")
    public ResponseEntity<Map<String, Object>> lookupPlayer(
        @RequestBody @Valid LookupRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.lookupPlayer(server, request.query(), request.shouldQueryMojang());
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @PostMapping("/lookup-profile")
    public ResponseEntity<Map<String, Object>> lookupProfile(
        @RequestBody @Valid LookupRequest request,
        @RequestParam(required = false) Integer punishmentLimit,
        @RequestParam(required = false) Integer noteLimit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.lookupProfile(server, request.query(), request.shouldQueryMojang(),
            punishmentLimit, noteLimit);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @PostMapping("/{uuid}/notes")
    public ResponseEntity<Map<String, Object>> createPlayerNote(
        @PathVariable String uuid,
        @RequestBody @Valid CreateNoteRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.createNote(server, uuid, request.text(), request.issuerName(),
            request.issuerId());
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping("/{uuid}/linked-accounts")
    public ResponseEntity<Map<String, Object>> getLinkedAccounts(
        @PathVariable String uuid,
        @RequestParam(required = false) @Min(RequestValidationLimits.PAGINATION_PAGE_MIN) Integer page,
        @RequestParam(required = false) @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) Integer limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = playerLookupService.getLinkedAccounts(server, uuid, page, limit);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping("/{uuid}/punishments")
    public ResponseEntity<Map<String, Object>> getPlayerPunishments(
        @PathVariable String uuid,
        @RequestParam(defaultValue = "1") @Min(RequestValidationLimits.PAGINATION_PAGE_MIN) int page,
        @RequestParam(defaultValue = "7") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.getPlayerPunishments(server, uuid, page, limit);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping("/{uuid}/notes")
    public ResponseEntity<Map<String, Object>> getPlayerNotes(
        @PathVariable String uuid,
        @RequestParam(defaultValue = "1") @Min(RequestValidationLimits.PAGINATION_PAGE_MIN) int page,
        @RequestParam(defaultValue = "7") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.PAGINATION_LIMIT_MAX) int limit,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.getPlayerNotes(server, uuid, page, limit);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping("/{uuid}/reports")
    public ResponseEntity<Map<String, Object>> getPlayerReports(
        @PathVariable String uuid,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return ResponseEntity.ok(minecraftPlayerService.getPlayerReports(server, uuid));
    }

    @PostMapping("/submit-ip-info")
    public ResponseEntity<Map<String, Object>> submitIpInfo(
        @RequestBody @Valid SubmitIpInfoRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return ResponseEntity.ok(minecraftPlayerService.submitIpInfo(
            server,
            request.minecraftUUID(),
            request.ip(),
            request.country(),
            request.region(),
            request.asn(),
            request.proxy(),
            request.hosting()
        ));
    }

    @PostMapping("/pardon")
    public ResponseEntity<Map<String, Object>> pardonPlayer(
        @RequestBody @Valid PardonPlayerRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        Map<String, Object> response = minecraftPlayerService.pardonPlayer(
            server,
            request.playerName(),
            request.punishmentType(),
            request.issuerName(),
            request.issuerId(),
            request.reason()
        );

        if (Objects.equals(response.get("status"), 404)) {
            return ResponseEntity.status(404).body(response);
        }
        return ResponseEntity.ok(response);
    }

    public record LoginRequest(
        @NotBlank String minecraftUUID,
        @NotBlank String username,
        @NotBlank String ip,
        Map<String, Object> ipInfo,
        String skinHash,
        String serverName,
        String serverInstanceId
    ) {
    }

    public record SubmitIpInfoRequest(
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String minecraftUUID,
        @NotBlank @Pattern(regexp = RegExpConstants.IP) String ip,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String country,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String region,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String asn,
        boolean proxy,
        boolean hosting
    ) {
    }

    public record DisconnectRequest(
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String minecraftUuid,
        @Min(0) long sessionDurationMs,
        String serverInstanceId
    ) {
    }

    public record UpdateServerRequest(
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String minecraftUuid,
        @NotBlank @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String serverName,
        String serverInstanceId
    ) {
    }

    public record LookupRequest(
        @NotBlank @Size(max = RequestValidationLimits.ADMIN_SEARCH_QUERY_MAX_LENGTH) String query,
        Boolean queryMojang
    ) {
        public boolean shouldQueryMojang() {
            return queryMojang == null || queryMojang;
        }
    }

    public record CreateNoteRequest(
        @NotBlank @Size(max = RequestValidationLimits.PLAYER_NOTE_TEXT_MAX_LENGTH) String text,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId
    ) {
    }

    public record PardonPlayerRequest(
        @NotBlank @Size(max = RequestValidationLimits.LOG_USERNAME_MAX_LENGTH) String playerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) String issuerName,
        @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) String issuerId,
        @Size(max = RequestValidationLimits.PUNISHMENT_TYPE_NAME_MAX_LENGTH) String punishmentType,
        @Size(max = RequestValidationLimits.PLAYER_MODIFICATION_REASON_MAX_LENGTH) String reason
    ) {
    }
}
