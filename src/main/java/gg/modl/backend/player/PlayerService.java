package gg.modl.backend.player;

import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.EnforcementCategory;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.dto.response.PlayerDetailResponse;
import gg.modl.backend.player.dto.response.PlayerSearchResult;
import gg.modl.backend.player.dto.response.PunishmentResponse;
import gg.modl.backend.player.service.PlayerStatusCalculator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.util.UuidUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlayerService {
    private final PlayerMongoRepository playerRepository;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentTypeService punishmentTypeService;
    private static final int SEARCH_RESULT_LIMIT = 20;
    private static final int SEARCH_CANDIDATE_LIMIT = 100;
    private static final int RANK_EXACT_CURRENT_USERNAME = 0;
    private static final int RANK_EXACT_PAST_USERNAME = 1;
    private static final int RANK_PREFIX_CURRENT_USERNAME = 2;
    private static final int RANK_PREFIX_PAST_USERNAME = 3;
    private static final int RANK_CONTAINS_CURRENT_USERNAME = 4;
    private static final int RANK_CONTAINS_PAST_USERNAME = 5;
    private static final int RANK_NO_MATCH = Integer.MAX_VALUE;

    public List<PlayerSearchResult> searchPlayers(Server server, String searchTerm) {
        String normalizedSearch = searchTerm == null ? "" : searchTerm.trim();
        if (normalizedSearch.isEmpty()) {
            return List.of();
        }

        List<Player> players;
        if (isUuid(normalizedSearch)) {
            players = playerRepository.findByMinecraftUuid(server, UuidUtil.normalizeUuid(normalizedSearch))
                .map(List::of)
                .orElseGet(List::of);
        } else {
            players = playerRepository.searchByUsernamePattern(server, normalizedSearch, SEARCH_CANDIDATE_LIMIT);
            String normalizedSearchLower = normalizedSearch.toLowerCase(Locale.ROOT);
            players = players.stream()
                .sorted(Comparator
                    .comparingInt((Player player) -> computeMatchRank(player, normalizedSearchLower))
                    .thenComparing((Player a, Player b) -> Long.compare(getLastLoginMillis(b), getLastLoginMillis(a)))
                    .thenComparing(player -> player.getMinecraftUuid().toString()))
                .limit(SEARCH_RESULT_LIMIT)
                .toList();
        }

        return players.stream()
            .map(player -> toPlayerSearchResult(server, player))
            .toList();
    }

    public Optional<Player> findBestByUsername(Server server, String username) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isEmpty()) {
            return Optional.empty();
        }

        String normalizedUsernameLower = normalizedUsername.toLowerCase(Locale.ROOT);
        List<Player> candidates = playerRepository.searchByUsernamePattern(server, normalizedUsername, SEARCH_CANDIDATE_LIMIT);

        return candidates.stream()
            .filter(player -> hasExactUsernameMatch(player, normalizedUsernameLower))
            .sorted(Comparator
                .comparingInt((Player player) -> exactUsernameMatchRank(player, normalizedUsernameLower))
                .thenComparing((Player a, Player b) -> Boolean.compare(isOnline(b), isOnline(a)))
                .thenComparing((Player a, Player b) -> Long.compare(getLastLoginMillis(b), getLastLoginMillis(a)))
                .thenComparing(player -> player.getMinecraftUuid().toString()))
            .findFirst()
            .or(() -> playerRepository.findByUsernameIgnoreCase(server, normalizedUsername));
    }

    private PlayerSearchResult toPlayerSearchResult(Server server, Player player) {
        List<UsernameEntry> usernames = player.getUsernames();
        String username = usernames == null || usernames.isEmpty() ? "Unknown" :
                          usernames.get(usernames.size() - 1).username();

        String status = calculatePlayerStatus(server, player);
        Date lastOnline = getLastOnline(player);
        boolean isOnline = Boolean.TRUE.equals(
            player.getData() != null ? player.getData().get("isOnline") : null);

        return new PlayerSearchResult(
            player.getMinecraftUuid().toString(),
            username,
            status,
            lastOnline,
            isOnline
        );
    }

    private String calculatePlayerStatus(Server server, Player player) {
        List<Punishment> punishments = player.getPunishments();
        for (Punishment punishment : punishments) {
            if (statusCalculator.isPunishmentActive(punishment)) {
                PunishmentType pt = punishmentTypeService.getPunishmentTypeByOrdinal(server, punishment.getTypeOrdinal()).orElse(null);
                String category = statusCalculator.getEffectiveCategory(pt, punishment.getData());
                if (EnforcementCategory.BAN.name().equals(category)) {
                    return "Banned";
                }
            }
        }

        if (isOnline(player)) {
            return "Online";
        }
        return "Offline";
    }

    private int computeMatchRank(Player player, String normalizedSearchLower) {
        List<UsernameEntry> usernames = player.getUsernames();
        if (usernames == null || usernames.isEmpty()) {
            return RANK_NO_MATCH;
        }

        String currentUsername = usernames.get(usernames.size() - 1).username();
        if (equalsIgnoreCase(currentUsername, normalizedSearchLower)) {
            return RANK_EXACT_CURRENT_USERNAME;
        }

        for (int i = 0; i < usernames.size() - 1; i++) {
            if (equalsIgnoreCase(usernames.get(i).username(), normalizedSearchLower)) {
                return RANK_EXACT_PAST_USERNAME;
            }
        }

        if (startsWithIgnoreCase(currentUsername, normalizedSearchLower)) {
            return RANK_PREFIX_CURRENT_USERNAME;
        }

        for (int i = 0; i < usernames.size() - 1; i++) {
            if (startsWithIgnoreCase(usernames.get(i).username(), normalizedSearchLower)) {
                return RANK_PREFIX_PAST_USERNAME;
            }
        }

        if (containsIgnoreCase(currentUsername, normalizedSearchLower)) {
            return RANK_CONTAINS_CURRENT_USERNAME;
        }

        for (int i = 0; i < usernames.size() - 1; i++) {
            if (containsIgnoreCase(usernames.get(i).username(), normalizedSearchLower)) {
                return RANK_CONTAINS_PAST_USERNAME;
            }
        }

        return RANK_NO_MATCH;
    }

    private boolean hasExactUsernameMatch(Player player, String normalizedSearchLower) {
        List<UsernameEntry> usernames = player.getUsernames();
        if (usernames == null || usernames.isEmpty()) {
            return false;
        }

        for (UsernameEntry username : usernames) {
            if (equalsIgnoreCase(username.username(), normalizedSearchLower)) {
                return true;
            }
        }
        return false;
    }

    private int exactUsernameMatchRank(Player player, String normalizedSearchLower) {
        List<UsernameEntry> usernames = player.getUsernames();
        if (usernames == null || usernames.isEmpty()) {
            return RANK_NO_MATCH;
        }

        String currentUsername = usernames.get(usernames.size() - 1).username();
        if (equalsIgnoreCase(currentUsername, normalizedSearchLower)) {
            return RANK_EXACT_CURRENT_USERNAME;
        }

        for (int i = 0; i < usernames.size() - 1; i++) {
            if (equalsIgnoreCase(usernames.get(i).username(), normalizedSearchLower)) {
                return RANK_EXACT_PAST_USERNAME;
            }
        }

        return RANK_NO_MATCH;
    }

    private boolean equalsIgnoreCase(String value, String normalizedSearchLower) {
        return value != null && value.toLowerCase(Locale.ROOT).equals(normalizedSearchLower);
    }

    private boolean startsWithIgnoreCase(String value, String normalizedSearchLower) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith(normalizedSearchLower);
    }

    private boolean containsIgnoreCase(String value, String normalizedSearchLower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedSearchLower);
    }

    private long getLastLoginMillis(Player player) {
        Date lastOnline = getLastOnline(player);
        return lastOnline != null ? lastOnline.getTime() : Long.MIN_VALUE;
    }

    private Date getLastOnline(Player player) {
        if (player.getData() == null) {
            return null;
        }
        Object lastLogin = player.getData().get("lastLogin");
        if (lastLogin instanceof Date) {
            return (Date) lastLogin;
        }
        return null;
    }

    private boolean isOnline(Player player) {
        Object isOnline = player.getData() != null ? player.getData().get("isOnline") : null;
        return Boolean.TRUE.equals(isOnline);
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public PlayerDetailResponse getPlayerDetails(Server server, UUID minecraftUuid) {
        Player player = findByMinecraftUuid(server, minecraftUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found"));
        return buildPlayerDetailResponse(server, player);
    }

    public Optional<Player> findByMinecraftUuid(Server server, UUID minecraftUuid) {
        return playerRepository.findByMinecraftUuid(server, minecraftUuid);
    }

    private PlayerDetailResponse buildPlayerDetailResponse(Server server, Player player) {
        List<Punishment> punishments = player.getPunishments();
        PlayerStatusCalculator.PlayerStatus status = statusCalculator.calculateStatus(server, punishments);

        List<PunishmentResponse> punishmentResponses = punishments.stream()
            .map(p -> toPunishmentResponse(server, p))
            .toList();

        List<IPEntry> sanitizedIps = (player.getIpAddresses() != null ? player.getIpAddresses() : List.<IPEntry>of()).stream()
            .map(ip -> IPEntry.builder()
                .ipAddress(null)
                .country(ip.getCountry())
                .region(ip.getRegion())
                .asn(ip.getAsn())
                .proxy(ip.isProxy())
                .hosting(ip.isHosting())
                .firstLogin(ip.getFirstLogin())
                .logins(ip.getLogins())
                .build())
            .toList();

        IPEntry latestIp = sanitizedIps.isEmpty() ? null :
                           sanitizedIps.get(sanitizedIps.size() - 1);

        String lastServer = player.getData() != null ? (String) player.getData().get("lastServer") : null;

        Object playtimeObj = player.getData() != null ? player.getData().get("totalPlaytimeSeconds") : null;
        double playtimeHours = 0;
        if (playtimeObj instanceof Number) {
            playtimeHours = ((Number) playtimeObj).doubleValue() / 3600.0;
        }

        return new PlayerDetailResponse(
            player.getId(),
            player.getMinecraftUuid().toString(),
            player.getUsernames(),
            player.getNotes(),
            sanitizedIps,
            punishmentResponses,
            player.getData(),
            status.social(),
            status.gameplay(),
            status.socialPoints(),
            status.gameplayPoints(),
            latestIp,
            lastServer,
            playtimeHours
        );
    }

    private PunishmentResponse toPunishmentResponse(Server server, Punishment punishment) {
        Map<String, Object> data = punishment.getData();
        boolean active = statusCalculator.isPunishmentActive(punishment);
        Date expires = statusCalculator.getEffectiveExpiry(punishment);
        int ordinal = punishment.getTypeOrdinal();

        PunishmentType punishmentType = punishmentTypeService.getPunishmentTypeByOrdinal(server, ordinal).orElse(null);
        String effectiveCategory = statusCalculator.getEffectiveCategory(punishmentType, data);

        return new PunishmentResponse(
            punishment.getId(),
            punishmentTypeService.getPunishmentTypeName(server, ordinal),
            ordinal,
            punishment.getIssuerName(),
            punishment.getIssued(),
            punishment.getStarted(),
            punishmentTypeService.isAppealable(server, ordinal),
            PunishmentData.getReason(data),
            PunishmentData.getSeverity(data),
            data != null ? resolveOffenderStatus(data) : null,
            active,
            expires,
            null,
            null,
            data != null ? PunishmentData.isAltBlocking(data) : null,
            data != null ? PunishmentData.isWipeAfterExpiry(data) : null,
            effectiveCategory,
            punishment.getModifications(),
            punishment.getNotes(),
            punishment.getEvidence(),
            punishment.getAttachedTicketIds()
        );
    }

    private static String resolveOffenderStatus(Map<String, Object> data) {
        String status = PunishmentData.getStatus(data);
        if (status != null) {
            return status;
        }
        String offenseLevel = PunishmentData.getOffenseLevel(data);
        if (offenseLevel != null) {
            return switch (offenseLevel.toLowerCase(Locale.ROOT)) {
                case "first" -> "low";
                default -> offenseLevel; // "medium" and "habitual" stay as-is
            };
        }
        return null;
    }

    public Player createPlayer(Server server, UUID minecraftUuid, String username) {
        return playerRepository.saveEntity(server, newPlayer(minecraftUuid, username));
    }

    private Player newPlayer(UUID minecraftUuid, String username) {
        return Player.builder()
            .id(PlayerDocumentIdGenerator.generate())
            .minecraftUuid(minecraftUuid)
            .usernames(new ArrayList<>(List.of(new UsernameEntry(username, new Date()))))
            .notes(new ArrayList<>())
            .ipAddresses(new ArrayList<>())
            .punishments(new ArrayList<>())
            .data(new HashMap<>())
            .build();
    }

    public record LoginResult(Player player, boolean isNewIp) {}

    public LoginResult loginPlayer(Server server, UUID minecraftUuid, String username, String ip) {
        return loginPlayer(server, minecraftUuid, username, ip, null, null, null);
    }

    public LoginResult loginPlayer(Server server, UUID minecraftUuid, String username, String ip, Map<String, Object> ipInfo) {
        return loginPlayer(server, minecraftUuid, username, ip, ipInfo, null, null);
    }

    public LoginResult loginPlayer(Server server, UUID minecraftUuid, String username, String ip, Map<String, Object> ipInfo, String skinHash) {
        return loginPlayer(server, minecraftUuid, username, ip, ipInfo, skinHash, null);
    }

    public LoginResult loginPlayer(Server server, UUID minecraftUuid, String username, String ip, Map<String, Object> ipInfo, String skinHash, String serverName) {
        Optional<Player> existingPlayer = findByMinecraftUuid(server, minecraftUuid);

        if (existingPlayer.isPresent()) {
            Player player = existingPlayer.get();
            boolean isNewIp = updatePlayerOnLogin(player, username, ip, ipInfo, skinHash, serverName);
            playerRepository.updateLoginState(server, player);
            return new LoginResult(player, isNewIp);
        }

        Player player = newPlayer(minecraftUuid, username);
        boolean isNewIp = addIpToPlayer(player, ip, ipInfo);
        updatePlayerDataOnLogin(player, skinHash, serverName);
        return new LoginResult(playerRepository.saveEntity(server, player), isNewIp);
    }

    public Player addUsername(Server server, UUID minecraftUuid, String username) {
        Player player = findByMinecraftUuid(server, minecraftUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found"));

        ensureUsernames(player).add(new UsernameEntry(username, new Date()));
        playerRepository.replaceUsernames(server, player);
        return player;
    }

    private List<UsernameEntry> ensureUsernames(Player player) {
        if (player.getUsernames() == null) {
            player.setUsernames(new ArrayList<>());
        }
        return player.getUsernames();
    }

    public Player addNote(Server server, UUID minecraftUuid, String text, String issuerName, String issuerId) {
        Player player = findByMinecraftUuid(server, minecraftUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found"));

        NoteEntry entry = NoteEntry.builder()
            .id(IdGenerator.generateShortId())
            .text(text)
            .date(new Date())
            .issuerName(issuerName)
            .issuerId(issuerId)
            .build();

        ensureNotes(player).add(entry);
        playerRepository.replaceNotes(server, player);
        return player;
    }

    private List<NoteEntry> ensureNotes(Player player) {
        if (player.getNotes() == null) {
            player.setNotes(new ArrayList<>());
        }
        return player.getNotes();
    }

    public Player addIp(Server server, UUID minecraftUuid, String ipAddress) {
        Player player = findByMinecraftUuid(server, minecraftUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found"));
        addIpToPlayer(player, ipAddress, null);
        playerRepository.replaceIpAddresses(server, player);
        return player;
    }

    /**
     * @return true if this IP was newly added, false if it already existed
     */
    private boolean addIpToPlayer(Player player, String ipAddress, Map<String, Object> ipInfo) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }

        Date now = new Date();
        List<IPEntry> ipAddresses = ensureIpAddresses(player);

        Optional<IPEntry> existingIp = ipAddresses.stream()
            .filter(ip -> ipAddress.equals(ip.getIpAddress()))
            .findFirst();

        if (existingIp.isPresent()) {
            IPEntry entry = existingIp.get();
            if (entry.getLogins() == null) {
                entry.setLogins(new ArrayList<>());
            }
            entry.getLogins().add(now);
            if (ipInfo != null) {
                applyIpMetadata(entry, ipInfo);
            }
            return false;
        }

        IPEntry.IPEntryBuilder builder = IPEntry.builder()
            .ipAddress(ipAddress)
            .firstLogin(now)
            .logins(new ArrayList<>(List.of(now)));

        if (ipInfo != null) {
            builder.country((String) ipInfo.get("country"))
                .region((String) ipInfo.get("region"))
                .asn((String) ipInfo.get("asn"))
                .proxy(Boolean.TRUE.equals(ipInfo.get("proxy")))
                .hosting(Boolean.TRUE.equals(ipInfo.get("hosting")));
        }

        ipAddresses.add(builder.build());
        return true;
    }

    private void applyIpMetadata(IPEntry ipEntry, Map<String, Object> ipInfo) {
        if (ipInfo == null) {
            return;
        }
        ipEntry.setCountry((String) ipInfo.get("country"));
        ipEntry.setRegion((String) ipInfo.get("region"));
        ipEntry.setAsn((String) ipInfo.get("asn"));
        ipEntry.setProxy(Boolean.TRUE.equals(ipInfo.get("proxy")));
        ipEntry.setHosting(Boolean.TRUE.equals(ipInfo.get("hosting")));
    }

    private List<IPEntry> ensureIpAddresses(Player player) {
        if (player.getIpAddresses() == null) {
            player.setIpAddresses(new ArrayList<>());
        }
        return player.getIpAddresses();
    }

    public void updateIpGeoData(Server server, String minecraftUuid, String ipAddress, Map<String, Object> ipInfo) {
        if (minecraftUuid == null || minecraftUuid.isBlank() || ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        Player player = playerRepository.findByMinecraftUuid(server, UuidUtil.normalizeUuid(minecraftUuid)).orElse(null);
        if (player == null) {
            return;
        }

        IPEntry ipEntry = ensureIpAddresses(player).stream()
            .filter(entry -> ipAddress.equals(entry.getIpAddress()))
            .findFirst()
            .orElse(null);
        if (ipEntry == null) {
            return;
        }

        applyIpMetadata(ipEntry, ipInfo);
        playerRepository.replaceIpAddresses(server, player);
    }

    private boolean updatePlayerOnLogin(Player player, String username, String ip, Map<String, Object> ipInfo, String skinHash, String serverName) {
        List<UsernameEntry> usernames = ensureUsernames(player);
        UsernameEntry currentEntry = usernames.isEmpty() ? null : usernames.get(usernames.size() - 1);
        String currentUsername = currentEntry != null ? currentEntry.username() : null;

        if (!username.equals(currentUsername)) {
            usernames.add(new UsernameEntry(username, new Date()));
        } else if (currentEntry != null && currentEntry.date() == null) {
            usernames.set(usernames.size() - 1, new UsernameEntry(username, new Date()));
        }

        boolean isNewIp = addIpToPlayer(player, ip, ipInfo);
        updatePlayerDataOnLogin(player, skinHash, serverName);
        return isNewIp;
    }

    private void updatePlayerDataOnLogin(Player player, String skinHash, String serverName) {
        Date now = new Date();
        Map<String, Object> data = ensureData(player);
        data.put("lastLogin", now);
        data.put("isOnline", true);

        if (!data.containsKey("firstJoin")) {
            data.put("firstJoin", now);
        }

        if (skinHash != null && !skinHash.isBlank()) {
            data.put("lastSkinHash", skinHash);
        }

        if (serverName != null && !serverName.isBlank()) {
            data.put("lastServer", serverName);
        }
    }

    private Map<String, Object> ensureData(Player player) {
        if (player.getData() == null) {
            player.setData(new HashMap<>());
        }
        return player.getData();
    }

}
