package gg.modl.backend.player.service;

import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.infrastructure.util.PaginationHelper;
import gg.modl.backend.infrastructure.util.UuidUtil;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlayerLookupService {
    private final PlayerMongoRepository playerRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final MojangApiService mojangApiService;
    private final PlayerStatusCalculator statusCalculator;
    private final IssuerNameResolver issuerNameResolver;
    private final StaffMongoRepository staffRepository;
    private final PlayerService playerService;

    public MinecraftPlayerService.ServiceResponse getPlayerByUuid(Server server, String uuid, Integer punishmentLimit, Integer noteLimit) {
        Player player = findPlayerByUuid(server, uuid).orElse(null);
        if (player == null) {
            return notFound("Player not found");
        }

        List<PunishmentType> punishmentTypes = punishmentTypeService.getPunishmentTypes(server);
        return ok(Map.of("status", 200, "profile", toPlayerProfile(server, player, punishmentTypes, punishmentLimit, noteLimit)));
    }

    public MinecraftPlayerService.ServiceResponse getPlayerByMinecraftUuid(Server server, String minecraftUuid, boolean queryMojang) {
        if (minecraftUuid == null || minecraftUuid.isBlank()) {
            return badRequest("minecraftUuid parameter required");
        }

        Player player = findPlayerByUuid(server, minecraftUuid).orElse(null);
        if (player == null && queryMojang) {
            Optional<MojangApiService.MojangProfile> profile = mojangApiService.lookupByUuid(minecraftUuid);
            if (profile.isPresent()) {
                return ok(Map.of(
                    "status", 200,
                    "message", "Player found via Mojang",
                    "player", toMojangProfile(profile.get())
                ));
            }
        }

        if (player == null) {
            return notFound("Player not found");
        }

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        return ok(Map.of(
            "status", 200,
            "message", "Player found",
            "player", toPlayerProfile(server, player, types)
        ));
    }

    public MinecraftPlayerService.ServiceResponse getPlayerByUsername(Server server, String username, boolean queryMojang) {
        Player player = findByUsername(server, username).orElse(null);
        if (player == null && queryMojang) {
            Optional<MojangApiService.MojangProfile> profile = mojangApiService.lookupByUsername(username);
            if (profile.isPresent()) {
                return ok(Map.of(
                    "status", 200,
                    "message", "Player found via Mojang",
                    "player", toMojangProfile(profile.get())
                ));
            }
        }

        if (player == null) {
            return notFound("Player not found");
        }

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        return ok(Map.of(
            "status", 200,
            "message", "Player found",
            "player", toPlayerProfile(server, player, types)
        ));
    }

    public MinecraftPlayerService.ServiceResponse lookupPlayer(Server server, String query, boolean shouldQueryMojang) {
        boolean isUuid = query.contains("-") && query.length() == 36;
        Player player = isUuid
                        ? findPlayerByUuid(server, query).orElse(null)
                        : findByUsername(server, query).orElse(null);

        if (player == null && shouldQueryMojang) {
            Optional<MojangApiService.MojangProfile> profile = isUuid
                                                               ? mojangApiService.lookupByUuid(query)
                                                               : mojangApiService.lookupByUsername(query);
            if (profile.isPresent()) {
                MojangApiService.MojangProfile mojangProfile = profile.get();
                Map<String, Object> lookupData = new LinkedHashMap<>();
                lookupData.put("minecraftUuid", mojangProfile.uuid().toString());
                lookupData.put("currentUsername", mojangProfile.name());
                lookupData.put("previousUsernames", List.of());
                lookupData.put("firstSeen", null);
                lookupData.put("lastSeen", null);
                lookupData.put("isOnline", false);
                lookupData.put("punishmentStats", Map.of(
                    "totalPunishments", 0,
                    "activePunishments", 0,
                    "bans", 0,
                    "mutes", 0,
                    "kicks", 0,
                    "warnings", 0
                ));
                lookupData.put("recentPunishments", List.of());
                return ok(Map.of(
                    "status", 200,
                    "message", "Player found via Mojang",
                    "data", lookupData
                ));
            }
        }

        if (player == null) {
            return notFound("Player not found");
        }

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        return ok(Map.of(
            "status", 200,
            "message", "Player found",
            "data", buildLookupResponse(server, player, types)
        ));
    }

    public MinecraftPlayerService.ServiceResponse lookupProfile(Server server, String query, boolean shouldQueryMojang, Integer punishmentLimit, Integer noteLimit) {
        boolean isUuid = query.contains("-") && query.length() == 36;
        Player player = isUuid
                        ? findPlayerByUuid(server, query).orElse(null)
                        : findByUsername(server, query).orElse(null);

        if (player == null && shouldQueryMojang) {
            Optional<MojangApiService.MojangProfile> mojangProfile = isUuid
                                                                     ? mojangApiService.lookupByUuid(query)
                                                                     : mojangApiService.lookupByUsername(query);
            if (mojangProfile.isPresent()) {
                Player mojangPlayer = findPlayerByUuid(server, mojangProfile.get().uuid().toString()).orElse(null);
                if (mojangPlayer != null) {
                    player = mojangPlayer;
                }
            }
        }

        if (player == null) {
            return notFound("Player not found");
        }

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        return ok(Map.of("status", 200, "profile", toPlayerProfile(server, player, types, punishmentLimit, noteLimit)));
    }

    Map<String, Object> toPlayerProfile(Server server, Player player, List<PunishmentType> punishmentTypes) {
        return toPlayerProfile(server, player, punishmentTypes, null, null);
    }

    Map<String, Object> toPlayerProfile(Server server, Player player, List<PunishmentType> punishmentTypes, Integer punishmentLimit, Integer noteLimit) {
        List<Map<String, Object>> usernames = safeUsernames(player).stream()
            .map(username -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("username", username.username());
                entry.put("date", username.date());
                return entry;
            })
            .toList();

        List<NoteEntry> allNotes = safeNotes(player);
        List<Map<String, Object>> notes = limitList(allNotes, noteLimit).stream()
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

        List<Map<String, Object>> ipAddresses = safeIpAddresses(player).stream()
            .map(ip -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("country", ip.getCountry());
                entry.put("region", ip.getRegion());
                entry.put("asn", ip.getAsn());
                entry.put("proxy", ip.isProxy());
                entry.put("hosting", ip.isHosting());
                entry.put("firstLogin", ip.getFirstLogin());
                entry.put("logins", ip.getLogins());
                return entry;
            })
            .toList();

        List<Punishment> allPunishments = safePunishments(player);
        Map<String, String> resolvedIssuers = resolveIssuersForPunishments(server, allPunishments);
        List<Map<String, Object>> punishments = limitList(allPunishments, punishmentLimit).stream()
            .map(punishment -> PunishmentMapper.toPunishmentMap(punishment, punishmentTypes, resolvedIssuers))
            .toList();

        List<Map<String, Object>> pendingNotifications = extractPendingNotifications(player);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", player.getId());
        profile.put("minecraftUuid", player.getMinecraftUuid().toString());
        profile.put("usernames", usernames);
        profile.put("notes", notes);
        profile.put("ipAddresses", ipAddresses);
        profile.put("punishments", punishments);
        profile.put("pendingNotifications", pendingNotifications);
        profile.put("data", player.getData());
        if (punishmentLimit != null) {
            profile.put("punishmentCount", allPunishments.size());
        }
        if (noteLimit != null) {
            profile.put("noteCount", allNotes.size());
        }
        return profile;
    }

    private Map<String, Object> buildLookupResponse(Server server, Player player, List<PunishmentType> types) {
        String currentUsername = PlayerDataUtils.extractLatestUsername(safeUsernames(player));
        List<String> previousUsernames = player.getUsernames()
            .stream()
            .map(username -> username.username())
            .skip(1)
            .toList();

        Date firstSeen = player.getUsernames().isEmpty() ? null : player.getUsernames().get(0).date();
        Date lastSeen = player.getUsernames().isEmpty() ? null : player.getUsernames().get(player.getUsernames().size() - 1).date();

        int totalPunishments = player.getPunishments().size();
        int activePunishments = (int) player.getPunishments()
            .stream()
            .filter(statusCalculator::isPunishmentActive)
            .count();

        Map<Integer, PunishmentType> typesByOrdinal = PunishmentTypeIndex.byOrdinal(types);

        int bans = 0;
        int mutes = 0;
        int kicks = 0;
        int warnings = 0;
        for (Punishment punishment : player.getPunishments()) {
            PunishmentType type = typesByOrdinal.get(punishment.getTypeOrdinal());
            if (type != null && type.isBan()) {
                bans++;
            } else if (type != null && type.isMute()) {
                mutes++;
            } else if (type != null && type.isKick()) {
                kicks++;
            } else {
                warnings++;
            }
        }

        List<Map<String, Object>> recentPunishments = player.getPunishments()
            .stream()
            .sorted((left, right) -> right.getIssued().compareTo(left.getIssued()))
            .limit(5)
            .map(punishment -> {
                PunishmentType matchedType = typesByOrdinal.get(punishment.getTypeOrdinal());
                String typeName = matchedType != null ? matchedType.getName() : "Unknown";

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("id", punishment.getId());
                response.put("type", typeName);
                response.put("issuer", punishment.getIssuerName());
                response.put("issuedAt", punishment.getIssued());
                response.put("expiresAt", statusCalculator.getEffectiveExpiry(punishment));
                response.put("isActive", statusCalculator.isPunishmentActive(punishment));
                return response;
            })
            .toList();

        String baseUrl = "https://" + server.getCustomDomain();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("minecraftUuid", player.getMinecraftUuid().toString());
        data.put("currentUsername", currentUsername);
        data.put("previousUsernames", previousUsernames);
        data.put("firstSeen", firstSeen);
        data.put("lastSeen", lastSeen);
        data.put("isOnline", player.getData().getOrDefault("isOnline", false));
        data.put("punishmentStats", Map.of(
            "totalPunishments", totalPunishments,
            "activePunishments", activePunishments,
            "bans", bans,
            "mutes", mutes,
            "kicks", kicks,
            "warnings", warnings
        ));
        data.put("recentPunishments", recentPunishments);
        data.put("profileUrl", baseUrl + "/player/" + player.getMinecraftUuid());
        data.put("punishmentsUrl", baseUrl + "/player/" + player.getMinecraftUuid() + "/punishments");
        return data;
    }

    private Map<String, Object> toMojangProfile(MojangApiService.MojangProfile profile) {
        return Map.of(
            "minecraftUuid", profile.uuid().toString(),
            "usernames", List.of(Map.of("username", profile.name())),
            "notes", List.of(),
            "ipAddresses", List.of(),
            "punishments", List.of(),
            "pendingNotifications", List.of(),
            "data", Map.of()
        );
    }

    private Optional<Player> findPlayerByUuid(Server server, String uuid) {
        return playerRepository.findByMinecraftUuid(server, UuidUtil.normalizeUuid(uuid));
    }

    private Optional<Player> findByUsername(Server server, String username) {
        return playerService.findBestByUsername(server, username);
    }

    private Map<String, String> resolveIssuersForPlayer(Server server, Player player) {
        return resolveIssuersForPunishments(server, safePunishments(player));
    }

    private Map<String, String> resolveIssuersForPunishments(Server server, List<Punishment> punishments) {
        Set<String> ids = new HashSet<>();
        for (Punishment p : punishments) {
            ids.addAll(PunishmentQueryService.collectIssuerIds(p));
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return issuerNameResolver.batchResolve(ids, server);
    }

    private List<UsernameEntry> safeUsernames(Player player) {
        return player.getUsernames() != null ? player.getUsernames() : List.of();
    }

    private List<NoteEntry> safeNotes(Player player) {
        return player.getNotes() != null ? player.getNotes() : List.of();
    }

    private List<Punishment> safePunishments(Player player) {
        return player.getPunishments() != null ? player.getPunishments() : List.of();
    }

    private List<IPEntry> safeIpAddresses(Player player) {
        return player.getIpAddresses() != null ? player.getIpAddresses() : List.of();
    }

    private <T> List<T> limitList(List<T> values, Integer limit) {
        if (limit == null) {
            return values;
        }
        return values.stream()
            .limit(Math.max(0, limit))
            .toList();
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

    private MinecraftPlayerService.ServiceResponse ok(Map<String, Object> body) {
        return new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, body);
    }

    private MinecraftPlayerService.ServiceResponse notFound(String message) {
        return new MinecraftPlayerService.ServiceResponse(HttpStatus.NOT_FOUND, Map.of("status", 404, "message", message));
    }

    public MinecraftPlayerService.ServiceResponse getLinkedAccounts(Server server, String uuid, Integer page, Integer limit) {
        MinecraftPlayerService.ServiceResponse fullResponse = getLinkedAccountsFull(server, uuid);
        if (fullResponse.status() != HttpStatus.OK || page == null || limit == null) {
            return fullResponse;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allAccounts = (List<Map<String, Object>>) fullResponse.body().get("linkedAccounts");
        PaginationHelper.PageResult<Map<String, Object>> result = PaginationHelper.paginate(allAccounts, page, limit);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("linkedAccounts", result.items());
        response.put("totalCount", result.totalCount());
        response.put("page", result.page());
        response.put("hasMore", result.hasMore());
        return ok(response);
    }

    private MinecraftPlayerService.ServiceResponse getLinkedAccountsFull(Server server, String uuid) {
        Player player = findPlayerByUuid(server, uuid).orElse(null);
        if (player == null) {
            return notFound("Player not found");
        }

        List<String> ips = safeIpAddresses(player).stream()
            .map(ip -> ip.getIpAddress())
            .toList();

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        Set<String> addedUuids = new HashSet<>();
        List<Map<String, Object>> linkedAccounts = new ArrayList<>();

        if (!ips.isEmpty()) {
            List<Player> relatedPlayers = playerRepository.findByIpAddressesExcludingUuid(server, ips, UuidUtil.normalizeUuid(uuid), 20);
            for (Player related : relatedPlayers) {
                linkedAccounts.add(toPlayerProfile(server, related, types));
                addedUuids.add(related.getMinecraftUuid().toString());
            }
        }

        if (player.getData() != null && player.getData().containsKey("linkedAccounts")) {
            Object rawLinked = player.getData().get("linkedAccounts");
            List<String> storedLinkedUuids = rawLinked instanceof List<?> list
                                             ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                                             : List.of();

            List<String> missingUuids = storedLinkedUuids.stream()
                .filter(linkedUuid -> !addedUuids.contains(linkedUuid))
                .toList();
            if (!missingUuids.isEmpty()) {
                List<Player> linkedPlayers = playerRepository.findByMinecraftUuids(server, missingUuids);
                for (Player linkedPlayer : linkedPlayers) {
                    linkedAccounts.add(toPlayerProfile(server, linkedPlayer, types));
                }
            }
        }

        return ok(Map.of("status", 200, "linkedAccounts", linkedAccounts));
    }

    private MinecraftPlayerService.ServiceResponse badRequest(String message) {
        return new MinecraftPlayerService.ServiceResponse(HttpStatus.BAD_REQUEST, Map.of("status", 400, "message", message));
    }

}
