package gg.modl.backend.admin.service;

import gg.modl.backend.database.mongo.repository.ServerDatabaseMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.server.ServerService;
import gg.modl.backend.server.data.ProvisioningStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.data.SubscriptionStatus;
import gg.modl.backend.server.service.ServerProvisioningService;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminServerService {
    private final ServerMongoRepository serverRepository;
    private final ServerDatabaseMongoRepository serverDatabaseRepository;
    private final ServerProvisioningService provisioningService;
    private final ServerService serverService;
    private static final long USAGE_STATS_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_USAGE_BATCH_SIZE = 50;

    public long countServers(String search, String plan, String status) {
        return serverRepository.countAdminServers(search, plan, status);
    }

    @Async
    public void refreshUsageStatsForActiveServers(int maxServers) {
        int boundedLimit = Math.max(1, Math.min(maxServers, 500));
        Date now = new Date();
        Date staleCutoff = new Date(now.getTime() - USAGE_STATS_TTL_MILLIS);

        List<Server> servers = serverRepository.findUsageRefreshCandidates(staleCutoff, boundedLimit);
        for (Server server : servers) {
            getOrComputeUsageStats(server, now, false);
        }
    }

    private ComputedUsage getOrComputeUsageStats(Server server, Date now, boolean forceRefresh) {
        long cachedUsers = server.getUserCount() != null ? server.getUserCount() : 0L;
        long cachedTickets = server.getTicketCount() != null ? server.getTicketCount() : 0L;

        if (!forceRefresh && isUsageStatsCacheFresh(server, now)) {
            return new ComputedUsage(cachedUsers, cachedTickets, server.getLastStatsUpdatedAt(), true);
        }

        if (server.getDatabaseName() == null || server.getDatabaseName().isBlank()) {
            persistUsageStats(server.getId(), cachedUsers, cachedTickets, now);
            return new ComputedUsage(cachedUsers, cachedTickets, now, false);
        }

        Optional<ServerDatabaseMongoRepository.UsageCounts> usageCounts = serverDatabaseRepository.readUsageCounts(server);
        if (usageCounts.isPresent()) {
            persistUsageStats(server.getId(), usageCounts.get().players(), usageCounts.get().tickets(), now);
            return new ComputedUsage(usageCounts.get().players(), usageCounts.get().tickets(), now, false);
        }

        log.warn("Failed to refresh usage stats for server {}", server.getServerName());
        Date updatedAt = server.getLastStatsUpdatedAt() != null ? server.getLastStatsUpdatedAt() : now;
        return new ComputedUsage(cachedUsers, cachedTickets, updatedAt, true);
    }

    private boolean isUsageStatsCacheFresh(Server server, Date now) {
        if (server.getLastStatsUpdatedAt() == null) {
            return false;
        }

        long ageMillis = now.getTime() - server.getLastStatsUpdatedAt().getTime();
        return ageMillis >= 0 && ageMillis <= USAGE_STATS_TTL_MILLIS;
    }

    private void persistUsageStats(String serverId, long userCount, long ticketCount, Date updatedAt) {
        serverRepository.updateUsageStats(serverId, userCount, ticketCount, updatedAt);
    }

    public @NotNull Map<String, UsageSummary> getUsageStatsForServerIds(@Nullable List<String> serverIds, boolean forceRefresh) {
        if (serverIds == null || serverIds.isEmpty()) {
            return Map.of();
        }

        List<String> filteredIds = serverIds.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .distinct()
            .limit(MAX_USAGE_BATCH_SIZE)
            .toList();

        if (filteredIds.isEmpty()) {
            return Map.of();
        }

        Date now = new Date();
        List<Server> servers = serverRepository.findUsageTargetsByIds(filteredIds);
        Map<String, UsageSummary> usageByServerId = new HashMap<>();

        for (Server server : servers) {
            ComputedUsage usage = getOrComputeUsageStats(server, now, forceRefresh);
            usageByServerId.put(server.getId(), new UsageSummary(
                usage.userCount(),
                usage.ticketCount(),
                usage.updatedAt(),
                usage.fromCache()
            ));
        }

        return usageByServerId;
    }

    public @NotNull Server createServer(@NotNull String serverName, @NotNull String customDomain, @NotNull String adminEmail, @Nullable String plan) {
        Date now = new Date();
        ServerPlan serverPlan = plan != null ? ServerPlan.valueOf(plan.trim().toUpperCase(Locale.ROOT)) : ServerPlan.FREE;
        Server server = new Server(serverName, customDomain, "server_" + customDomain, adminEmail, false, serverPlan);
        server.setProvisioningStatus(ProvisioningStatus.PENDING);
        server.setSubscriptionStatus(SubscriptionStatus.INACTIVE);
        server.setCreatedAt(now);
        server.setUpdatedAt(now);
        Server saved = serverRepository.saveEntity(server);
        serverService.evictAllServerCaches();
        return saved;
    }

    public String exportServersCsv(String plan, String status) {
        List<Server> servers = findServers(null, plan, status, "createdAt", "desc", 0, 10000);
        StringBuilder csv = new StringBuilder();
        csv.append("id,serverName,customDomain,adminEmail,plan,provisioningStatus,emailVerified,createdAt\n");
        for (Server s : servers) {
            csv.append(String.format("%s,%s,%s,%s,%s,%s,%s,%s\n",
                s.getId(), s.getServerName(), s.getCustomDomain(), s.getAdminEmail(),
                s.getPlan(), s.getProvisioningStatus(), s.getEmailVerified(), s.getCreatedAt()));
        }
        return csv.toString();
    }

    public List<Server> findServers(String search, String plan, String status, String sortField, String sortOrder, int skip, int limit) {
        return serverRepository.findAdminServers(search, plan, status, sortField, sortOrder, skip, limit);
    }

    public @NotNull Optional<Server> findById(@NotNull String id) {
        return serverRepository.findById(id);
    }

    public @NotNull Server save(@NotNull Server server) {
        Server saved = serverRepository.saveEntity(server);
        serverService.evictAllServerCaches();
        return saved;
    }

    public @Nullable Server updateById(@NotNull String id, @NotNull Map<String, Object> updateData) {
        return serverRepository.updateAllowedFields(id, updateData).orElse(null);
    }

    public boolean deleteById(String id) {
        return serverRepository.deleteByServerId(id);
    }

    public long bulkDelete(List<String> serverIds) {
        return serverRepository.deleteByServerIds(serverIds);
    }

    public long bulkSuspend(List<String> serverIds) {
        return serverRepository.bulkSuspend(serverIds, new Date());
    }

    public long bulkActivate(List<String> serverIds) {
        long modified = serverRepository.bulkActivate(serverIds, new Date());

        List<Server> servers = serverRepository.findProvisioningCandidatesByIds(serverIds);
        for (Server server : servers) {
            try {
                if (server.getDatabaseName() != null) {
                    provisioningService.provision(server);
                }
            } catch (Exception e) {
                log.warn("Failed to provision server {}", server.getId(), e);
            }
        }

        return modified;
    }

    public long bulkUpdatePlan(List<String> serverIds, String plan) {
        ServerPlan parsedPlan = ServerPlan.valueOf(plan.trim().toUpperCase(Locale.ROOT));
        return serverRepository.bulkUpdatePlan(serverIds, parsedPlan, new Date());
    }

    public Map<String, Object> getServerStats(Server server) {
        Map<String, Object> stats = new HashMap<>();
        Date now = new Date();

        if (server.getDatabaseName() == null || server.getDatabaseName().isBlank()) {
            long cachedUsers = server.getUserCount() != null ? server.getUserCount() : 0L;
            long cachedTickets = server.getTicketCount() != null ? server.getTicketCount() : 0L;
            stats.put("totalPlayers", cachedUsers);
            stats.put("totalTickets", cachedTickets);
            stats.put("totalLogs", 0);
            stats.put("lastActivity", server.getUpdatedAt());
            stats.put("databaseSize", 0);
            return stats;
        }

        Optional<ServerDatabaseMongoRepository.ServerDatabaseStats> databaseStats = serverDatabaseRepository.readStats(server);
        if (databaseStats.isPresent()) {
            ServerDatabaseMongoRepository.ServerDatabaseStats loadedStats = databaseStats.get();
            persistUsageStats(server.getId(), loadedStats.players(), loadedStats.tickets(), now);

            stats.put("totalPlayers", loadedStats.players());
            stats.put("totalTickets", loadedStats.tickets());
            stats.put("totalLogs", loadedStats.logs());
            stats.put("lastActivity", server.getLastActivityAt() != null ? server.getLastActivityAt() : server.getUpdatedAt());
            stats.put("databaseSize", loadedStats.storageSize());
            return stats;
        }

        log.warn("Failed to get stats for server {}", server.getServerName());
        long cachedUsers = server.getUserCount() != null ? server.getUserCount() : 0L;
        long cachedTickets = server.getTicketCount() != null ? server.getTicketCount() : 0L;
        stats.put("totalPlayers", cachedUsers);
        stats.put("totalTickets", cachedTickets);
        stats.put("totalLogs", 0);
        stats.put("lastActivity", server.getUpdatedAt());
        stats.put("databaseSize", 0);
        return stats;
    }

    public void resetServerDatabase(Server server) {
        if (server.getDatabaseName() != null) {
            if (serverDatabaseRepository.dropDatabase(server)) {
                log.info("Dropped database {} for server {}", server.getDatabaseName(), server.getServerName());
            } else {
                log.warn("Failed to drop database {}", server.getDatabaseName());
            }
        }

        serverRepository.resetAfterDatabaseDrop(server.getId(), new Date());
    }

    private record ComputedUsage(long userCount, long ticketCount, Date updatedAt, boolean fromCache) {}

    public record UsageSummary(long userCount, long ticketCount, Date updatedAt, boolean fromCache) {}
}
