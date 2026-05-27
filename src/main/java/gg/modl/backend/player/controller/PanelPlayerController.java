package gg.modl.backend.player.controller;

import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.dto.request.AddEvidenceRequest;
import gg.modl.backend.player.dto.request.AddIpRequest;
import gg.modl.backend.player.dto.request.AddModificationRequest;
import gg.modl.backend.player.dto.request.AddNoteRequest;
import gg.modl.backend.player.dto.request.AddPunishmentNoteRequest;
import gg.modl.backend.player.dto.request.AddUsernameRequest;
import gg.modl.backend.player.dto.request.CreatePlayerRequest;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.dto.request.ModifyPunishmentTicketsRequest;
import gg.modl.backend.player.dto.response.LinkedAccountResponse;
import gg.modl.backend.player.dto.response.PlayerDetailResponse;
import gg.modl.backend.player.dto.response.PlayerSearchResult;
import gg.modl.backend.player.dto.response.PunishmentResponse;
import gg.modl.backend.player.dto.response.PunishmentSearchResult;
import gg.modl.backend.player.service.AccountLinkingService;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.player.service.PunishmentQueryService;
import gg.modl.backend.replay.dto.PlayerReplayResponse;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@RequestMapping(RESTMappingV1.PANEL_PLAYERS)
@RequiredArgsConstructor
@Validated
public class PanelPlayerController {
    private final PlayerService playerService;
    private final PunishmentQueryService punishmentQueryService;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final PunishmentEvidenceService punishmentEvidenceService;
    private final PunishmentMutationService punishmentMutationService;
    private final AccountLinkingService accountLinkingService;
    private final ReplayService replayService;

    @GetMapping
    public ResponseEntity<List<PlayerSearchResult>> searchPlayers(
        @RequestParam @Size(min = 2) String search,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<PlayerSearchResult> results = playerService.searchPlayers(server, search);

        return ResponseEntity.ok(results);
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<PlayerDetailResponse> getPlayer(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        return ResponseEntity.ok(playerService.getPlayerDetails(server, UUID.fromString(uuid)));
    }

    @PostMapping
    public ResponseEntity<SimpleResponse> createPlayer(
        @RequestBody @Valid CreatePlayerRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        playerService.createPlayer(
            server,
            UUID.fromString(createRequest.minecraftUuid()),
            createRequest.username()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(new SimpleResponse(true));
    }

    @PostMapping("/{uuid}/usernames")
    public ResponseEntity<SimpleResponse> addUsername(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @RequestBody @Valid AddUsernameRequest addRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        playerService.addUsername(server, UUID.fromString(uuid), addRequest.username());
        return ResponseEntity.ok(new SimpleResponse(true));
    }

    @PostMapping("/{uuid}/notes")
    public ResponseEntity<SimpleResponse> addNote(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @RequestBody @Valid AddNoteRequest addRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        playerService.addNote(
            server,
            UUID.fromString(uuid),
            addRequest.text(),
            addRequest.issuerName(),
            addRequest.issuerId()
        );
        return ResponseEntity.ok(new SimpleResponse(true));
    }

    @PostMapping("/{uuid}/ips")
    public ResponseEntity<SimpleResponse> addIp(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @RequestBody @Valid AddIpRequest addRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        playerService.addIp(server, UUID.fromString(uuid), addRequest.ipAddress());
        return ResponseEntity.ok(new SimpleResponse(true));
    }

    @PostMapping("/{uuid}/punishments")
    public ResponseEntity<?> createPunishment(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @RequestBody @Valid CreatePunishmentRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String email = RequestUtil.getSessionEmail(request);

        punishmentLifecycleService.validatePunishmentPermission(server, email, createRequest.typeOrdinal());
        punishmentLifecycleService.createPunishment(server, UUID.fromString(uuid), createRequest);
        return ResponseEntity.ok(new SimpleResponse(true));
    }

    @PostMapping("/{uuid}/punishments/{punishmentId}/modifications")
    public ResponseEntity<SimpleResponse> addModification(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @PathVariable String punishmentId,
        @RequestBody @Valid AddModificationRequest modRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        punishmentMutationService.addModification(
            server,
            UUID.fromString(uuid),
            punishmentId,
            modRequest
        );
        return ResponseEntity.ok(new SimpleResponse(true));
    }

    @GetMapping("/{uuid}/punishments/active")
    public ResponseEntity<List<PunishmentResponse>> getActivePunishments(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<PunishmentResponse> punishments = punishmentQueryService.getActivePunishments(
            server,
            UUID.fromString(uuid)
        );

        return ResponseEntity.ok(punishments);
    }

    @GetMapping({"/punishments/{punishmentId}", "/punishment/{punishmentId}"})
    public ResponseEntity<PunishmentResponse> getPunishmentById(
        @PathVariable String punishmentId,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        return ResponseEntity.ok(punishmentQueryService.getPunishmentById(server, punishmentId));
    }

    @GetMapping("/punishments/search")
    public ResponseEntity<List<PunishmentSearchResult>> searchPunishments(
        @RequestParam @Size(min = 2) String q,
        @RequestParam(defaultValue = "false") boolean activeOnly,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<PunishmentSearchResult> results = punishmentQueryService.searchPunishments(server, q, activeOnly);
        return ResponseEntity.ok(results);
    }

    @PostMapping("/{uuid}/punishments/{punishmentId}/notes")
    public ResponseEntity<SimpleResponse> addPunishmentNote(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @PathVariable String punishmentId,
        @RequestBody @Valid AddPunishmentNoteRequest noteRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        punishmentEvidenceService.addPunishmentNote(
            server,
            UUID.fromString(uuid),
            punishmentId,
            noteRequest.text(),
            noteRequest.issuerName(),
            noteRequest.issuerId()
        );
        return ResponseEntity.ok(new SimpleResponse(true));
    }

    @PostMapping("/{uuid}/punishments/{punishmentId}/evidence")
    public ResponseEntity<SimpleResponse> addEvidence(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @PathVariable String punishmentId,
        @RequestBody @Valid AddEvidenceRequest evidenceRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        punishmentEvidenceService.addEvidence(
            server,
            UUID.fromString(uuid),
            punishmentId,
            evidenceRequest
        );
        return ResponseEntity.ok(new SimpleResponse(true));
    }

    @PostMapping("/{uuid}/punishments/{punishmentId}/tickets")
    public ResponseEntity<SimpleResponse> modifyPunishmentTickets(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @PathVariable String punishmentId,
        @RequestBody @Valid ModifyPunishmentTicketsRequest ticketRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        punishmentMutationService.modifyPunishmentTickets(
            server,
            UUID.fromString(uuid),
            punishmentId,
            ticketRequest
        );
        return ResponseEntity.ok(new SimpleResponse(true));
    }

    @GetMapping("/{uuid}/linked")
    public ResponseEntity<Map<String, Object>> getLinkedAccounts(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<LinkedAccountResponse> linkedAccounts = accountLinkingService.getLinkedAccounts(
            server,
            UUID.fromString(uuid)
        );

        return ResponseEntity.ok(Map.of("linkedAccounts", linkedAccounts));
    }

    @GetMapping("/{uuid}/replays")
    public ResponseEntity<List<PlayerReplayResponse>> getPlayerReplays(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(replayService.listPlayerReplays(server, uuid));
    }

    @GetMapping("/punishments/{punishmentId}/linked-bans")
    public ResponseEntity<List<Map<String, Object>>> getLinkedBans(
        @PathVariable String punishmentId,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        List<Map<String, Object>> linkedBans = punishmentQueryService.getLinkedBansForParent(server, punishmentId);
        return ResponseEntity.ok(linkedBans);
    }

    @PostMapping("/{uuid}/find-linked")
    public ResponseEntity<Map<String, Object>> findAndLinkAccounts(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        AccountLinkingService.LinkingResult result = accountLinkingService.findAndLinkAccounts(
            server,
            UUID.fromString(uuid)
        );

        return ResponseEntity.ok(Map.of(
            "success", result.success(),
            "message", result.message(),
            "linkedAccountsFound", result.linkedAccountsFound()
        ));
    }

    public record SimpleResponse(boolean success) {}
}
