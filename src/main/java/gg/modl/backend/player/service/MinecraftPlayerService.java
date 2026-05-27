package gg.modl.backend.player.service;

import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.util.PaginationHelper;
import gg.modl.backend.infrastructure.util.UuidUtil;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.EnforcementCategory;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.dto.request.AcknowledgeNotificationsRequest;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MinecraftPlayerService {
    private final PlayerService playerService;
    private final PlayerMongoRepository playerRepository;
    private final TicketMongoRepository ticketRepository;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentTypeService punishmentTypeService;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final AccountLinkingService accountLinkingService;
    private final IssuerNameResolver issuerNameResolver;
    private final StaffMongoRepository staffRepository;

    public ServiceResponse login(
        Server server,
        UUID playerUuid,
        String username,
        String ip,
        Map<String, Object> ipInfo,
        String skinHash,
        String serverName
    ) {
        PlayerService.LoginResult loginResult = playerService.loginPlayer(server, playerUuid, username, ip, ipInfo, skinHash, serverName);
        Player player = loginResult.player();
        boolean isNewIp = loginResult.isNewIp();
        accountLinkingService.findAndLinkAccounts(server, playerUuid);

        player = findPlayerByUuid(server, playerUuid.toString()).orElse(player);

        player = promotePunishments(server, player, playerUuid, username, skinHash);

        List<PunishmentType> punishmentTypes = punishmentTypeService.getPunishmentTypes(server);
        Map<String, String> resolvedIssuers = resolveIssuersForPlayer(server, player);
        List<Map<String, Object>> activePunishments = collectActivePunishments(player, punishmentTypes, resolvedIssuers);

        return buildLoginResponse(server, player, playerUuid, ip, username, activePunishments, isNewIp);
    }

    private Player promotePunishments(Server server, Player player, UUID playerUuid, String username, String skinHash) {
        List<String> promoted = punishmentLifecycleService.promoteUnstartedPunishments(server, player);
        List<String> autoPardoned = punishmentLifecycleService.checkRestrictionAutoPardons(server, player, username, skinHash);
        List<String> linkedBans = punishmentLifecycleService.enforceAltBlockingBans(server, player);

        if (!promoted.isEmpty() || !autoPardoned.isEmpty() || !linkedBans.isEmpty()) {
            return findPlayerByUuid(server, playerUuid.toString()).orElse(player);
        }
        return player;
    }

    private List<Map<String, Object>> collectActivePunishments(
        Player player,
        List<PunishmentType> punishmentTypes,
        Map<String, String> resolvedIssuers
    ) {
        List<Map<String, Object>> activePunishments = new ArrayList<>();
        Map<Integer, PunishmentType> typesByOrdinal = PunishmentTypeIndex.byOrdinal(punishmentTypes);

        for (Punishment punishment : player.getPunishments()) {
            if (!statusCalculator.isPunishmentActive(punishment)) {
                continue;
            }

            PunishmentType punishmentType = typesByOrdinal.get(punishment.getTypeOrdinal());
            if (punishmentType != null && punishmentType.isKick()) {
                continue;
            }

            activePunishments.add(PunishmentMapper.toSimplePunishment(punishment, punishmentTypes, statusCalculator, resolvedIssuers));
        }

        for (Punishment punishment : player.getPunishments()) {
            if (punishment.getTypeOrdinal() != 0 || punishment.getStarted() != null) {
                continue;
            }
            activePunishments.add(PunishmentMapper.toSimplePunishment(punishment, punishmentTypes, statusCalculator, resolvedIssuers));
        }

        return deduplicateActivePunishments(activePunishments);
    }

    private List<Map<String, Object>> deduplicateActivePunishments(List<Map<String, Object>> punishments) {
        Map<String, Map<String, Object>> oldestByCategory = new LinkedHashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> punishment : punishments) {
            String category = (String) punishment.get("category");
            if (EnforcementCategory.BAN.name().equals(category) || EnforcementCategory.MUTE.name().equals(category)) {
                Map<String, Object> existing = oldestByCategory.get(category);
                if (existing == null) {
                    oldestByCategory.put(category, punishment);
                } else {
                    long existingIssued = existing.get("issuedAt") instanceof Number number ? number.longValue() : 0L;
                    long currentIssued = punishment.get("issuedAt") instanceof Number number ? number.longValue() : 0L;
                    if (currentIssued < existingIssued) {
                        oldestByCategory.put(category, punishment);
                    }
                }
            } else {
                result.add(punishment);
            }
        }

        result.addAll(oldestByCategory.values());
        return result;
    }

    private ServiceResponse buildLoginResponse(
        Server server,
        Player player,
        UUID playerUuid,
        String ip,
        String username,
        List<Map<String, Object>> activePunishments,
        boolean isNewIp
    ) {
        List<Map<String, Object>> pendingNotifications = extractPendingNotifications(player);

        List<String> pendingIpLookups = new ArrayList<>();
        if (ip != null && isNewIp) {
            boolean ipNeedsLookup = player.getIpAddresses()
                .stream()
                .anyMatch(playerIp -> playerIp.getIpAddress().equals(ip) && playerIp.getCountry() == null);
            if (ipNeedsLookup) {
                pendingIpLookups.add(ip);
            }
        }

        List<Map<String, Object>> pendingStatWipes = new ArrayList<>();
        for (Punishment punishment : player.getPunishments()) {
            Map<String, Object> data = punishment.getData();
            if (data == null
                || !PunishmentData.isWipeAfterExpiry(data)
                || PunishmentData.isStatWipeCompleted(data)
                || !statusCalculator.isPunishmentNaturallyExpired(punishment)) {
                continue;
            }

            pendingStatWipes.add(Map.of(
                "minecraftUuid", playerUuid.toString(),
                "username", PlayerDataUtils.extractLatestUsername(safeUsernames(player)),
                "punishmentId", punishment.getId()
            ));
        }

        boolean isNewPlayer = player.getUsernames().size() == 1;
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("status", isNewPlayer ? 201 : 200);
        responseBody.put("activePunishments", activePunishments);
        responseBody.put("pendingNotifications", pendingNotifications);
        if (!pendingIpLookups.isEmpty()) {
            responseBody.put("pendingIpLookups", pendingIpLookups);
        }
        if (!pendingStatWipes.isEmpty()) {
            responseBody.put("pendingStatWipes", pendingStatWipes);
        }

        return new ServiceResponse(isNewPlayer ? HttpStatus.CREATED : HttpStatus.OK, responseBody);
    }

    private List<Map<String, Object>> extractPendingNotifications(Player player) {
        if (player.getData() == null) {
            return List.of();
        }

        Object rawPending = player.getData().getOrDefault("pendingNotifications", Collections.emptyList());
        if (!(rawPending instanceof List<?> list)) {
            return List.of();
        }

        return list.stream()
            .filter(entry -> entry instanceof Map<?, ?>)
            .map(entry -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> notification = (Map<String, Object>) entry;
                return notification;
            })
            .toList();
    }

    private List<UsernameEntry> safeUsernames(Player player) {
        return player.getUsernames() != null ? player.getUsernames() : List.of();
    }

    private Optional<Player> findPlayerByUuid(Server server, String uuid) {
        return playerRepository.findByMinecraftUuid(server, UuidUtil.normalizeUuid(uuid));
    }

    private Map<String, String> resolveIssuersForPlayer(Server server, Player player) {
        Set<String> ids = new HashSet<>();
        for (Punishment p : player.getPunishments()) {
            ids.addAll(PunishmentQueryService.collectIssuerIds(p));
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return issuerNameResolver.batchResolve(ids, server);
    }

    public Map<String, Object> disconnect(Server server, String minecraftUuid, long sessionDurationMs) {
        playerRepository.markDisconnected(server, UuidUtil.normalizeUuid(minecraftUuid), sessionDurationMs);
        return Map.of("status", 200, "success", true);
    }

    public Map<String, Object> updateServer(Server server, String minecraftUuid, String serverName) {
        playerRepository.updateLastServer(server, UuidUtil.normalizeUuid(minecraftUuid), serverName);
        return Map.of("status", 200, "success", true);
    }

    public Map<String, Object> getOnlinePlayers(Server server) {
        List<Map<String, Object>> players = playerRepository.findOnlinePlayers(server, 500)
            .stream()
            .map(player -> {
                Date joinedAt = player.getData() != null ? (Date) player.getData().get("lastLogin") : null;
                Object playtimeObj = player.getData() != null ? player.getData().get("totalPlaytimeSeconds") : null;
                long totalPlaytimeMs = playtimeObj instanceof Number number ? number.longValue() * 1000 : 0L;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("uuid", player.getMinecraftUuid().toString());
                entry.put("username", PlayerDataUtils.extractLatestUsername(safeUsernames(player)));
                entry.put("joinedAt", joinedAt);
                entry.put("totalPlaytimeMs", totalPlaytimeMs);
                return entry;
            })
            .toList();

        return Map.of("status", 200, "players", players);
    }

    public ServiceResponse createNote(Server server, String uuid, String text, String issuerName, String issuerId) {
        Player player = findPlayerByUuid(server, uuid).orElse(null);
        if (player == null) {
            return notFound("Player not found");
        }

        ensurePlayerNotes(player).add(NoteEntry.builder()
            .id(IdGenerator.generateShortId())
            .text(text)
            .date(new Date())
            .issuerName(issuerId != null ? null : issuerName)
            .issuerId(issuerId)
            .build());
        playerRepository.replaceNotes(server, player);

        return ok(Map.of(
            "status", 200,
            "success", true,
            "message", "Note added"
        ));
    }

    private List<NoteEntry> ensurePlayerNotes(Player player) {
        if (player.getNotes() == null) {
            player.setNotes(new ArrayList<>());
        }
        return player.getNotes();
    }

    public ServiceResponse acknowledgeNotifications(Server server, AcknowledgeNotificationsRequest request) {
        return acknowledgeNotifications(server, request.playerUuid(), request.notificationIds(), request.acknowledgedAt());
    }

    public ServiceResponse acknowledgeNotifications(
        Server server,
        String playerUuid,
        List<String> notificationIds,
        String acknowledgedAt
    ) {
        Player player = findPlayerByUuid(server, playerUuid).orElse(null);
        if (player == null) {
            return ok(Map.of(
                "status", 200,
                "success", true,
                "message", "No player found, nothing to acknowledge"
            ));
        }

        List<Map<String, Object>> pendingNotifications = extractPendingNotifications(player);
        List<Map<String, Object>> remainingNotifications = pendingNotifications.stream()
            .filter(notification -> {
                Object notificationId = notification.get("id");
                return notificationId == null || !notificationIds.contains(notificationId.toString());
            })
            .toList();

        ensurePlayerData(player).put("pendingNotifications", remainingNotifications);
        playerRepository.replacePendingNotifications(server, player, remainingNotifications);

        return ok(Map.of(
            "status", 200,
            "success", true,
            "message", "Acknowledged " + (pendingNotifications.size() - remainingNotifications.size()) + " notification(s)"
        ));
    }

    private Map<String, Object> ensurePlayerData(Player player) {
        if (player.getData() == null) {
            player.setData(new LinkedHashMap<>());
        }
        return player.getData();
    }

    public ServiceResponse getPlayerPunishments(Server server, String uuid, int page, int limit) {
        Player player = findPlayerByUuid(server, uuid).orElse(null);
        if (player == null) {
            return notFound("Player not found");
        }

        List<PunishmentType> punishmentTypes = punishmentTypeService.getPunishmentTypes(server);
        Map<String, String> resolvedIssuers = resolveIssuersForPlayer(server, player);

        List<Map<String, Object>> allPunishments = player.getPunishments()
            .stream()
            .sorted((a, b) -> b.getIssued().compareTo(a.getIssued()))
            .map(p -> PunishmentMapper.toPunishmentMap(p, punishmentTypes, resolvedIssuers))
            .toList();

        PaginationHelper.PageResult<Map<String, Object>> result = PaginationHelper.paginate(allPunishments, page, limit);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("punishments", result.items());
        response.put("totalCount", result.totalCount());
        response.put("page", result.page());
        response.put("hasMore", result.hasMore());
        return ok(response);
    }

    public ServiceResponse getPlayerNotes(Server server, String uuid, int page, int limit) {
        Player player = findPlayerByUuid(server, uuid).orElse(null);
        if (player == null) {
            return notFound("Player not found");
        }

        List<Map<String, Object>> allNotes = player.getNotes()
            .stream()
            .sorted((a, b) -> b.getDate().compareTo(a.getDate()))
            .map(note -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", note.getId());
                entry.put("text", note.getText());
                entry.put("date", note.getDate());
                entry.put("issuerName", note.getIssuerName());
                entry.put("issuerId", note.getIssuerId());
                return entry;
            })
            .toList();

        PaginationHelper.PageResult<Map<String, Object>> result = PaginationHelper.paginate(allNotes, page, limit);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("notes", result.items());
        response.put("totalCount", result.totalCount());
        response.put("page", result.page());
        response.put("hasMore", result.hasMore());
        return ok(response);
    }

    public Map<String, Object> getPlayerReports(Server server, String uuid) {
        List<Map<String, Object>> reports = ticketRepository.findReportedPlayerTickets(server, UuidUtil.normalizeUuid(uuid), 50)
            .stream()
            .map(ticket -> {
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("id", ticket.getId());
                report.put("type", ticket.getType() != null ? ticket.getType().getId() : null);
                report.put("reporterName", ticket.getCreatorName());
                report.put("reporterUuid", ticket.getCreatorUuid());
                report.put("subject", ticket.getSubject());
                report.put("status", ticket.getStatus() != null ? ticket.getStatus().getId() : null);
                report.put("priority", ticket.getPriority() != null ? ticket.getPriority().getId() : null);
                report.put("createdAt", ticket.getCreated());
                return report;
            })
            .toList();

        return Map.of("status", 200, "reports", reports);
    }

    public Map<String, Object> submitIpInfo(
        Server server,
        String minecraftUuid,
        String ip,
        String country,
        String region,
        String asn,
        boolean proxy,
        boolean hosting
    ) {
        playerService.updateIpGeoData(server, UuidUtil.normalizeUuid(minecraftUuid), ip, Map.of(
            "country", country != null ? country : "",
            "region", region != null ? region : "",
            "asn", asn != null ? asn : "",
            "proxy", proxy,
            "hosting", hosting
        ));
        return Map.of("status", 200, "success", true);
    }

    public Map<String, Object> pardonPlayer(Server server, String playerName, String punishmentType, String issuerName, String issuerId, String reason) {
        Player player = playerService.findBestByUsername(server, playerName).orElse(null);
        if (player == null) {
            return Map.of("status", 404, "message", "Player not found");
        }

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        int pardoned = 0;

        for (Punishment punishment : player.getPunishments()) {
            if (isAlreadyPardoned(punishment)) {
                continue;
            }

            boolean isActive = statusCalculator.isPunishmentActive(punishment);
            Map<String, Object> punishmentData = punishment.getData();
            boolean isUnstarted = PunishmentStatus.UNSTARTED.equals(PunishmentData.getStatus(punishmentData));

            boolean shouldPardon;
            if (punishmentType == null) {
                shouldPardon = isActive || isUnstarted;
            } else {
                String requestedType = punishmentType.toLowerCase(Locale.ROOT);
                String effectiveCategory = statusCalculator.getEffectiveCategory(punishment, types);
                shouldPardon = ("ban".equals(requestedType) && EnforcementCategory.BAN.name().equals(effectiveCategory) && (isActive || isUnstarted))
                               || ("mute".equals(requestedType) && EnforcementCategory.MUTE.name().equals(effectiveCategory) && (isActive || isUnstarted));
            }

            if (!shouldPardon) {
                continue;
            }

            PunishmentQueryService.PunishmentOperationResult result = punishmentLifecycleService.pardonPunishment(
                server,
                punishment.getId(),
                issuerName,
                issuerId,
                reason
            );
            if (result.status() == PunishmentQueryService.PunishmentOperationStatus.SUCCESS) {
                pardoned++;
            }
        }

        return Map.of(
            "status", 200,
            "success", pardoned > 0,
            "pardonedCount", pardoned,
            "message", pardoned > 0
                       ? "Pardoned " + pardoned + " punishment(s)"
                       : "No punishments found to pardon"
        );
    }

    private boolean isAlreadyPardoned(Punishment punishment) {
        return punishment.getModifications()
            .stream()
            .anyMatch(modification ->
                PunishmentModificationType.isPardon(modification.type()));
    }

    private ServiceResponse ok(Map<String, Object> body) {
        return new ServiceResponse(HttpStatus.OK, body);
    }

    private ServiceResponse notFound(String message) {
        return new ServiceResponse(HttpStatus.NOT_FOUND, Map.of("status", 404, "message", message));
    }

    public record ServiceResponse(HttpStatus status, Map<String, Object> body) {
    }

}
