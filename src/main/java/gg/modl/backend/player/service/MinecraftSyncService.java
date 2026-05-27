package gg.modl.backend.player.service;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import gg.modl.backend.database.mongo.repository.MigrationMongoRepository;
import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerInstanceSnapshotMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.infrastructure.util.UuidUtil;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.EnforcementCategory;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.proto.modl.v1.SimplePunishment;
import gg.modl.proto.modl.v1.SyncActiveStaffMember;
import gg.modl.proto.modl.v1.SyncData;
import gg.modl.proto.modl.v1.SyncMigrationTask;
import gg.modl.proto.modl.v1.SyncModifiedPunishment;
import gg.modl.proto.modl.v1.SyncPendingPunishment;
import gg.modl.proto.modl.v1.SyncPlayerNotification;
import gg.modl.proto.modl.v1.SyncResponse;
import gg.modl.proto.modl.v1.SyncStaff2faVerification;
import gg.modl.proto.modl.v1.SyncStaffNotification;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class MinecraftSyncService {
    private final PlayerMongoRepository playerRepository;
    private final StaffMongoRepository staffRepository;
    private final ServerMongoRepository serverRepository;
    private final MigrationMongoRepository migrationRepository;
    private final ServerInstanceSnapshotMongoRepository serverInstanceSnapshotRepository;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentTypeService punishmentTypeService;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final MinecraftChatLogService minecraftChatLogService;
    private final IssuerNameResolver issuerNameResolver;
    private final SyncStaffEventService syncStaffEventService;
    private final SyncActiveStaffService syncActiveStaffService;

    public SyncResponse sync(
        Server server,
        String lastSyncTimestamp,
        List<OnlinePlayerInput> onlinePlayers,
        String serverName,
        List<ChatLogInput> chatLogs,
        List<CommandLogInput> commandLogs,
        ServerStatusInput serverStatus,
        String clientIp
    ) {
        Instant now = Instant.now();

        serverRepository.updateFirst(
            Query.query(Criteria.where("_id").is(server.getId())),
            new Update()
                .set("lastActivityAt", Date.from(now))
                .set("onlinePlayerCount", onlinePlayers != null ? (long) onlinePlayers.size() : 0L)
        );

        Instant lastSync = lastSyncTimestamp != null
                           ? Instant.parse(lastSyncTimestamp)
                           : now.minusSeconds(30);

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        Map<Integer, PunishmentType> typesByOrdinal = PunishmentTypeIndex.byOrdinal(types);
        List<SyncPendingPunishment> pendingPunishments = new ArrayList<>();
        List<SyncModifiedPunishment> recentlyModifiedPunishments = new ArrayList<>();
        List<SyncPlayerNotification> playerNotifications = new ArrayList<>();
        List<SyncStaffNotification> pardonNotifications = new ArrayList<>();

        Set<String> onlineUuids = new HashSet<>();
        if (onlinePlayers != null) {
            for (OnlinePlayerInput onlinePlayer : onlinePlayers) {
                if (onlinePlayer.uuid() != null) {
                    onlineUuids.add(UuidUtil.normalizeUuid(onlinePlayer.uuid()));
                }
            }
        }

        markOfflinePlayers(server, onlineUuids, serverName, Date.from(now));

        if (!onlineUuids.isEmpty()) {
            List<Player> players = playerRepository.findByMinecraftUuids(server, onlineUuids);

            Set<String> allIssuerIds = new HashSet<>();
            for (Player p : players) {
                for (Punishment pun : p.getPunishments()) {
                    if (pun.getIssuerId() != null) {
                        allIssuerIds.add(pun.getIssuerId());
                    }
                    for (PunishmentModification m : pun.getModifications()) {
                        if (m.issuerId() != null) {
                            allIssuerIds.add(m.issuerId());
                        }
                    }
                }
            }
            Map<String, String> resolvedIssuers = allIssuerIds.isEmpty()
                                                  ? Map.of()
                                                  : issuerNameResolver.batchResolve(allIssuerIds, server);

            for (Player player : players) {
                List<String> promoted = punishmentLifecycleService.promoteUnstartedPunishments(server, player);
                if (!promoted.isEmpty()) {
                    player = playerRepository.findByMinecraftUuid(server, player.getMinecraftUuid().toString()).orElse(null);
                    if (player == null) {
                        continue;
                    }
                }

                String uuid = player.getMinecraftUuid().toString();
                String username = PlayerDataUtils.extractLatestUsername(player.getUsernames());

                Set<String> categoriesWithActiveStarted = new HashSet<>();
                Map<String, Punishment> oldestUnstartedPerCategory = new LinkedHashMap<>();
                Set<String> pardonedCategories = new HashSet<>();

                for (Punishment punishment : player.getPunishments()) {
                    boolean active = statusCalculator.isPunishmentActive(punishment);
                    String category = statusCalculator.getEffectiveCategory(punishment, types);

                    boolean recentlyModified = punishment.getModifications()
                        .stream()
                        .anyMatch(mod -> mod.date() != null && mod.date().toInstant().isAfter(lastSync));
                    if (recentlyModified) {
                        recentlyModifiedPunishments.add(SyncModifiedPunishment.newBuilder()
                            .setMinecraftUuid(uuid)
                            .setUsername(username)
                            .setPunishment(SimplePunishmentProtoMapper.toPunishmentWithModifications(punishment))
                            .build());

                        collectPardonNotifications(punishment, username, lastSync, typesByOrdinal, resolvedIssuers, pardonNotifications);
                    }

                    boolean recentlyPardoned = punishment.getModifications()
                        .stream()
                        .anyMatch(mod -> mod.date() != null
                                         && mod.date().toInstant().isAfter(lastSync)
                                         && PunishmentModificationType.isPardon(mod.type()));
                    if (recentlyPardoned && category != null) {
                        pardonedCategories.add(category);
                    }

                    if (punishment.getTypeOrdinal() == 0 && punishment.getStarted() == null) {
                        pendingPunishments.add(toPendingPunishment(uuid, username, punishment, typesByOrdinal, resolvedIssuers));
                        continue;
                    }

                    if (!active) {
                        continue;
                    }

                    if (punishment.getStarted() != null
                        && punishment.getIssued() != null
                        && punishment.getIssued().toInstant().isAfter(lastSync)
                        && category != null) {
                        pendingPunishments.add(toPendingPunishment(uuid, username, punishment, typesByOrdinal, resolvedIssuers));
                    }

                    if (category != null && punishment.getStarted() != null) {
                        categoriesWithActiveStarted.add(category);
                    } else if (category != null && punishment.getStarted() == null) {
                        Punishment existing = oldestUnstartedPerCategory.get(category);
                        if (existing == null || punishment.getIssued().before(existing.getIssued())) {
                            oldestUnstartedPerCategory.put(category, punishment);
                        }
                    }
                }

                for (Map.Entry<String, Punishment> entry : oldestUnstartedPerCategory.entrySet()) {
                    if (!categoriesWithActiveStarted.contains(entry.getKey())) {
                        pendingPunishments.add(toPendingPunishment(uuid, username, entry.getValue(), typesByOrdinal, resolvedIssuers));
                    }
                }

                if (!pardonedCategories.isEmpty()) {
                    for (Punishment punishment : player.getPunishments()) {
                        if (!statusCalculator.isPunishmentActive(punishment) || punishment.getStarted() == null) {
                            continue;
                        }

                        String category = statusCalculator.getEffectiveCategory(punishment, types);
                        if (category != null && pardonedCategories.contains(category)) {
                            pendingPunishments.add(toPendingPunishment(uuid, username, punishment, typesByOrdinal, resolvedIssuers));
                            pardonedCategories.remove(category);
                        }
                    }
                }

                Object rawPending = player.getData().get("pendingNotifications");
                if (rawPending instanceof List<?> pendingList) {
                    for (Object item : pendingList) {
                        if (!(item instanceof Map<?, ?> notification)) {
                            continue;
                        }
                        playerNotifications.add(toPlayerNotification(uuid, notification));
                    }
                }
            }
        }

        pendingPunishments = deduplicatePendingPunishments(pendingPunishments);

        List<SyncStaffNotification> staffNotifications = new ArrayList<>(
            syncStaffEventService.collectStaffEvents(server, lastSync, types)
        );
        staffNotifications.addAll(pardonNotifications);

        Map<String, String> onlinePlayerIps = new HashMap<>();
        if (onlinePlayers != null) {
            for (OnlinePlayerInput onlinePlayer : onlinePlayers) {
                if (onlinePlayer.uuid() != null && onlinePlayer.ipAddress() != null) {
                    onlinePlayerIps.put(UuidUtil.normalizeUuid(onlinePlayer.uuid()), onlinePlayer.ipAddress());
                }
            }
        }

        List<SyncActiveStaffMember> activeStaffMembers = syncActiveStaffService.getActiveStaffMembers(server, onlinePlayerIps);

        if (chatLogs != null && !chatLogs.isEmpty()) {
            minecraftChatLogService.submitChatLogs(server, chatLogs.stream()
                .map(entry -> new MinecraftChatLogService.ChatLogCommand(
                    entry.uuid(),
                    entry.username(),
                    entry.message(),
                    entry.timestamp(),
                    entry.server()
                ))
                .toList());
        }
        if (commandLogs != null && !commandLogs.isEmpty()) {
            minecraftChatLogService.submitCommandLogs(server, commandLogs.stream()
                .map(entry -> new MinecraftChatLogService.CommandLogCommand(
                    entry.uuid(),
                    entry.username(),
                    entry.command(),
                    entry.timestamp(),
                    entry.server()
                ))
                .toList());
        }

        SyncData.Builder dataBuilder = SyncData.newBuilder()
            .addAllPendingPunishments(pendingPunishments)
            .addAllRecentlyModifiedPunishments(recentlyModifiedPunishments)
            .addAllPlayerNotifications(playerNotifications)
            .addAllActiveStaffMembers(activeStaffMembers)
            .addAllStaffNotifications(staffNotifications);

        if (server.getStaffPermissionsUpdatedAt() != null) {
            dataBuilder.setStaffPermissionsUpdatedAt(server.getStaffPermissionsUpdatedAt().getTime());
        }
        if (server.getPunishmentTypesUpdatedAt() != null) {
            dataBuilder.setPunishmentTypesUpdatedAt(server.getPunishmentTypesUpdatedAt().getTime());
        }

        try {
            List<Staff> pendingStaff = staffRepository.findWithPendingTwoFactorDelivery(server);
            if (!pendingStaff.isEmpty()) {
                for (Staff staff : pendingStaff) {
                    dataBuilder.addStaff2FaVerifications(SyncStaff2faVerification.newBuilder()
                        .setMinecraftUuid(staff.getAssignedMinecraftUuid() != null ? staff.getAssignedMinecraftUuid() : "")
                        .build());
                }
                staffRepository.clearPendingTwoFactorDelivery(server);
            }
        } catch (Exception e) {
            log.warn("Failed to process 2FA verifications during sync", e);
        }

        try {
            Optional<MigrationStatus> activeMigration = migrationRepository.findActiveMigration(server);
            activeMigration.ifPresent(migration -> dataBuilder.setMigrationTask(SyncMigrationTask.newBuilder()
                .setTaskId(migration.getTaskId() != null ? migration.getTaskId() : "")
                .setType(migration.getType() != null ? migration.getType() : "")
                .build()));
        } catch (Exception e) {
            log.warn("Failed to check active migration during sync", e);
        }

        if (serverStatus != null) {
            try {
                long epochSeconds = now.getEpochSecond();
                Date fiveMinBoundary = Date.from(Instant.ofEpochSecond((epochSeconds / 300) * 300));
                serverInstanceSnapshotRepository.upsertServerEntry(
                    fiveMinBoundary,
                    server.getId(),
                    serverName,
                    serverStatus.onlinePlayerCount(),
                    serverStatus.platformType(),
                    serverStatus.serverVersion(),
                    clientIp,
                    serverStatus.pluginVersion(),
                    Date.from(now)
                );
            } catch (Exception e) {
                log.warn("Failed to upsert server instance snapshot during sync", e);
            }
        }

        return SyncResponse.newBuilder()
            .setTimestamp(now.toString())
            .setData(dataBuilder.build())
            .build();
    }

    private SyncPendingPunishment toPendingPunishment(
        String uuid,
        String username,
        Punishment punishment,
        Map<Integer, PunishmentType> typesByOrdinal,
        Map<String, String> resolvedIssuers
    ) {
        return SyncPendingPunishment.newBuilder()
            .setMinecraftUuid(uuid)
            .setUsername(username)
            .setPunishment(SimplePunishmentProtoMapper.toSimplePunishment(punishment, typesByOrdinal, statusCalculator, resolvedIssuers))
            .build();
    }

    private SyncPlayerNotification toPlayerNotification(String targetPlayerUuid, Map<?, ?> notification) {
        SyncPlayerNotification.Builder builder = SyncPlayerNotification.newBuilder()
            .setTargetPlayerUuid(targetPlayerUuid);

        Object id = notification.get("id");
        if (id != null) {
            builder.setId(id.toString());
        }
        Object message = notification.get("message");
        if (message != null) {
            builder.setMessage(message.toString());
        }
        Object type = notification.get("type");
        if (type != null) {
            builder.setType(type.toString());
        }
        Object timestamp = notification.get("timestamp");
        if (timestamp instanceof Number number) {
            builder.setTimestamp(number.longValue());
        } else if (timestamp instanceof Date date) {
            builder.setTimestamp(date.getTime());
        }
        Object data = notification.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            builder.setData(StructMapper.toStruct(dataMap));
        }
        return builder.build();
    }

    private void collectPardonNotifications(
        Punishment punishment,
        String username,
        Instant lastSync,
        Map<Integer, PunishmentType> typesByOrdinal,
        Map<String, String> resolvedIssuers,
        List<SyncStaffNotification> notifications
    ) {
        PunishmentType punishmentType = typesByOrdinal.get(punishment.getTypeOrdinal());
        String punishmentTypeName = punishmentType != null ? punishmentType.getName() : "punishment";

        for (PunishmentModification modification : punishment.getModifications()) {
            if (modification.date() == null || !modification.date().toInstant().isAfter(lastSync)) {
                continue;
            }
            if (!PunishmentModificationType.isPardon(modification.type())) {
                continue;
            }
            String pardoner = issuerNameResolver.resolve(modification.issuerId(), modification.issuerName(), resolvedIssuers);
            notifications.add(SyncStaffNotification.newBuilder()
                .setId("pardon_" + punishment.getId())
                .setType("PUNISHMENT_PARDONED")
                .setMessage(pardoner + ": pardoned " + username + "'s " + punishmentTypeName)
                .setTimestamp(modification.date().getTime())
                .build());
        }
    }

    private void markOfflinePlayers(Server server, Set<String> onlineUuids, String serverName, Date logoutTime) {
        playerRepository.markStalePlayersOffline(server, onlineUuids, serverName, logoutTime);
    }

    private List<SyncPendingPunishment> deduplicatePendingPunishments(List<SyncPendingPunishment> punishments) {
        Map<String, SyncPendingPunishment> oldestByPlayerCategory = new LinkedHashMap<>();
        List<SyncPendingPunishment> result = new ArrayList<>();

        for (SyncPendingPunishment entry : punishments) {
            SimplePunishment punishment = entry.getPunishment();
            String category = punishment.getCategory();

            if (EnforcementCategory.BAN.name().equals(category) || EnforcementCategory.MUTE.name().equals(category)) {
                String key = entry.getMinecraftUuid() + "|" + category;
                SyncPendingPunishment existing = oldestByPlayerCategory.get(key);
                if (existing == null || punishment.getIssuedAt() < existing.getPunishment().getIssuedAt()) {
                    oldestByPlayerCategory.put(key, entry);
                }
            } else {
                result.add(entry);
            }
        }

        result.addAll(oldestByPlayerCategory.values());
        return result;
    }

    public SyncResponse syncV2(
        Server server,
        String lastSyncTimestamp,
        List<OnlinePlayerInput> onlinePlayers,
        String serverName,
        List<ChatLogInput> chatLogs,
        List<CommandLogInput> commandLogs,
        String clientIp
    ) {
        SyncResponse response = sync(
            server,
            lastSyncTimestamp,
            onlinePlayers,
            serverName,
            chatLogs,
            commandLogs,
            null,
            clientIp
        );

        return relativizeTicketUrls(response);
    }

    private SyncResponse relativizeTicketUrls(SyncResponse response) {
        SyncData.Builder dataBuilder = response.getData().toBuilder();

        rewriteStaffNotificationUrls(dataBuilder);
        rewritePlayerNotificationUrls(dataBuilder);

        return response.toBuilder()
            .setData(dataBuilder.build())
            .build();
    }

    private void rewriteStaffNotificationUrls(SyncData.Builder dataBuilder) {
        for (int i = 0; i < dataBuilder.getStaffNotificationsCount(); i++) {
            SyncStaffNotification notification = dataBuilder.getStaffNotifications(i);
            if (!notification.hasData()) {
                continue;
            }
            Struct updated = withRelativeTicketUrl(notification.getData());
            if (updated != notification.getData()) {
                dataBuilder.setStaffNotifications(i, notification.toBuilder().setData(updated).build());
            }
        }
    }

    private void rewritePlayerNotificationUrls(SyncData.Builder dataBuilder) {
        for (int i = 0; i < dataBuilder.getPlayerNotificationsCount(); i++) {
            SyncPlayerNotification notification = dataBuilder.getPlayerNotifications(i);
            if (!notification.hasData()) {
                continue;
            }
            Struct updated = withRelativeTicketUrl(notification.getData());
            if (updated != notification.getData()) {
                dataBuilder.setPlayerNotifications(i, notification.toBuilder().setData(updated).build());
            }
        }
    }

    private Struct withRelativeTicketUrl(Struct data) {
        Value ticketUrl = data.getFieldsOrDefault("ticketUrl", null);
        if (ticketUrl == null || ticketUrl.getKindCase() != Value.KindCase.STRING_VALUE) {
            return data;
        }
        String original = ticketUrl.getStringValue();
        String relative = extractRelativePath(original);
        if (relative.equals(original)) {
            return data;
        }
        return data.toBuilder()
            .putFields("ticketUrl", Value.newBuilder().setStringValue(relative).build())
            .build();
    }

    private String extractRelativePath(String fullUrl) {
        try {
            return URI.create(fullUrl).getPath();
        } catch (Exception e) {
            int idx = fullUrl.indexOf("/", fullUrl.indexOf("://") + 3);
            return idx >= 0 ? fullUrl.substring(idx) : fullUrl;
        }
    }

    public record ServerStatusInput(int onlinePlayerCount, int maxPlayers, String serverVersion, String platformType, String pluginVersion) {
    }

    public record OnlinePlayerInput(String uuid, String username, String ipAddress) {
    }

    public record ChatLogInput(String uuid, String username, String message, long timestamp, String server) {
    }

    public record CommandLogInput(String uuid, String username, String command, long timestamp, String server) {
    }

}
