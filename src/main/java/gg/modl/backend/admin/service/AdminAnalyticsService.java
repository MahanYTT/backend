package gg.modl.backend.admin.service;

import gg.modl.backend.analytics.data.MetricSnapshot;
import gg.modl.backend.analytics.data.ServerInstanceSnapshot;
import gg.modl.backend.database.mongo.repository.GlobalMongoAdminRepository;
import gg.modl.backend.database.mongo.repository.MetricSnapshotMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerInstanceSnapshotMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository.DateServersResult;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository.DateValueResult;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository.NameValueResult;
import gg.modl.backend.infrastructure.util.DateRangeUtil;
import gg.modl.backend.server.data.Server;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAnalyticsService {
    private final ServerMongoRepository serverRepository;
    private final MetricSnapshotMongoRepository metricSnapshotRepository;
    private final ServerInstanceSnapshotMongoRepository serverInstanceSnapshotRepository;
    private final GlobalMongoAdminRepository globalMongoAdminRepository;
    private final AdminServerService adminServerService;

    public @NotNull Map<String, Object> getDashboard(@Nullable String range) {
        int days = DateRangeUtil.resolveRangeDays(range);
        Instant now = Instant.now();
        Date startDate = Date.from(now.minus(days, ChronoUnit.DAYS));
        Date previousStartDate = Date.from(now.minus(days * 2L, ChronoUnit.DAYS));

        ServerMongoRepository.DashboardStats stats = serverRepository.aggregateDashboardStats(startDate, previousStartDate);

        long totalServers = stats.totalServers();
        int refreshLimit = totalServers <= 200 ? (int) Math.max(totalServers, 1) : 50;
        adminServerService.refreshUsageStatsForActiveServers(refreshLimit);

        long activeServers = stats.activeServers();
        long totalUsers = stats.totalUsers();
        long totalTickets = stats.totalTickets();

        long currentPeriodServers = stats.currentPeriodServers();
        long previousPeriodServers = stats.previousPeriodServers();
        double serverGrowthRate = previousPeriodServers > 0
                                  ? ((currentPeriodServers - previousPeriodServers) / (double) previousPeriodServers) * 100
                                  : (currentPeriodServers > 0 ? 100 : 0);

        List<NameValueResult> planResults = serverRepository.aggregatePlanCounts();
        List<NameValueResult> statusResults = serverRepository.aggregateProvisioningStatusCounts();
        List<DateServersResult> registrationTrend = serverRepository.findRegistrationTrend(startDate);
        List<Server> topServers = serverRepository.findTopCompletedVerifiedByUserCount(10);

        long serversWithData = stats.serversWithData();
        double avgPlayersPerServer = serversWithData > 0 ? (double) totalUsers / serversWithData : 0;
        double avgTicketsPerServer = serversWithData > 0 ? (double) totalTickets / serversWithData : 0;

        Date snapshotCutoff = Date.from(now.minus(10, ChronoUnit.MINUTES));
        Date last24h = Date.from(now.minus(24, ChronoUnit.HOURS));

        List<MetricSnapshot> metricSnapshots = metricSnapshotRepository.findSinceOrdered(last24h);
        List<Map<String, Object>> serverActivity = metricSnapshots.stream().map(s -> Map.<String, Object>of(
            "date", s.getDate().toInstant().toString(),
            "activeServers", s.getActiveServers()
        )).toList();

        List<ServerInstanceSnapshot> instanceSnapshots = serverInstanceSnapshotRepository.findSinceOrdered(last24h);

        ServerInstanceSnapshot latestSnapshot = !instanceSnapshots.isEmpty()
            && !instanceSnapshots.getLast().getDate().before(snapshotCutoff)
            ? instanceSnapshots.getLast()
            : null;

        List<Map<String, Object>> liveServers;
        if (latestSnapshot != null && latestSnapshot.getServers() != null && !latestSnapshot.getServers().isEmpty()) {
            List<String> serverIds = latestSnapshot.getServers().stream()
                .map(ServerInstanceSnapshot.ServerEntry::getServerId)
                .toList();
            Map<String, String> serverNameMap = serverRepository.findUsageTargetsByIds(serverIds).stream()
                .collect(Collectors.toMap(Server::getId, Server::getServerName, (a, b) -> a));

            liveServers = latestSnapshot.getServers().stream().map(srv -> {
                Map<String, Object> map = new HashMap<>();
                map.put("serverId", srv.getServerId());
                map.put("serverName", serverNameMap.getOrDefault(srv.getServerId(), srv.getServerName()));
                map.put("playerCount", srv.getPlayerCount());
                map.put("platform", srv.getPlatform());
                map.put("version", srv.getVersion());
                map.put("pluginVersion", srv.getPluginVersion());
                return map;
            }).toList();
        } else {
            liveServers = List.of();
        }

        int totalPlayerCount = latestSnapshot != null
            ? sumPlayerCount(latestSnapshot)
            : 0;

        List<Map<String, Object>> playerActivity = instanceSnapshots.stream().map(s -> Map.<String, Object>of(
            "date", s.getDate().toInstant().toString(),
            "players", sumPlayerCount(s)
        )).toList();

        Map<String, Object> usageStatistics = new HashMap<>();
        usageStatistics.put("topServersByUsers", topServers);
        usageStatistics.put("serverActivity", serverActivity);
        usageStatistics.put("liveServers", liveServers);
        usageStatistics.put("totalPlayerCount", totalPlayerCount);
        usageStatistics.put("playerActivity", playerActivity);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", Map.of(
            "overview", Map.of(
                "totalServers", totalServers,
                "activeServers", activeServers,
                "totalUsers", totalUsers,
                "totalTickets", totalTickets,
                "serverGrowthRate", String.format("%.2f", serverGrowthRate),
                "userGrowthRate", "0.00",
                "avgPlayersPerServer", String.format("%.1f", avgPlayersPerServer),
                "avgTicketsPerServer", String.format("%.1f", avgTicketsPerServer)
            ),
            "serverMetrics", Map.of(
                "byPlan", planResults,
                "byStatus", statusResults,
                "registrationTrend", registrationTrend
            ),
            "usageStatistics", usageStatistics,
            "systemHealth", Map.of("errorRates", Collections.emptyList())
        ));
        return response;
    }

    private int sumPlayerCount(ServerInstanceSnapshot snapshot) {
        return snapshot.getServers() != null
            ? snapshot.getServers().stream().mapToInt(ServerInstanceSnapshot.ServerEntry::getPlayerCount).sum()
            : 0;
    }

    public @NotNull Map<String, Object> getActivity(@Nullable String range) {
        int days = DateRangeUtil.resolveRangeDays(range);
        Date startDate = Date.from(Instant.now().minus(days, ChronoUnit.DAYS));

        List<MetricSnapshot> snapshots = metricSnapshotRepository.findSinceOrdered(startDate);
        List<ServerInstanceSnapshot> instanceSnapshots = serverInstanceSnapshotRepository.findSinceOrdered(startDate);

        long totalServers = serverRepository.countAll();
        long totalPlayers = serverRepository.getUsageTotals().totalUsers();

        Map<String, Integer> playersByHour = new HashMap<>();
        for (ServerInstanceSnapshot inst : instanceSnapshots) {
            String hourKey = inst.getDate().toInstant().truncatedTo(ChronoUnit.HOURS).toString();
            int players = sumPlayerCount(inst);
            playersByHour.merge(hourKey, players, Math::max);
        }

        List<Map<String, Object>> activityData = snapshots.stream().map(s -> {
            String dateKey = s.getDate().toInstant().toString();
            int onlinePlayers = playersByHour.getOrDefault(dateKey, 0);
            return Map.<String, Object>of(
                "date", dateKey,
                "activeServers", s.getActiveServers(),
                "onlinePlayers", onlinePlayers
            );
        }).toList();

        return Map.of("success", true, "data", activityData,
            "totalPlayers", totalPlayers, "totalServers", totalServers);
    }

    public Map<String, Object> getUsage() {
        Date thirtyDaysAgo = Date.from(Instant.now().minus(30, ChronoUnit.DAYS));

        long activeServers = serverRepository.countActiveSince(thirtyDaysAgo);
        long totalServers = serverRepository.countAll();
        long storageSize = globalMongoAdminRepository.getStorageSize();

        return Map.of(
            "success", true,
            "data", Map.of(
                "userEngagement", Map.of("monthlyActiveServers", activeServers),
                "resourceUtilization", Map.of(
                    "storage", storageSize,
                    "storagePercent", totalServers > 0 ? (storageSize / (totalServers * 104857600.0)) * 100 : 0,
                    "apiCalls", 0,
                    "databaseQueries", 0
                )
            )
        );
    }

    public @NotNull Map<String, Object> getHistorical(@Nullable String metric, @Nullable String range) {
        if (metric == null || (!metric.equals("servers") && !metric.equals("users") && !metric.equals("tickets"))) {
            return Map.of("success", false, "error", "Invalid metric type");
        }

        int days = DateRangeUtil.resolveRangeDays(range);
        Date startDate = Date.from(Instant.now().minus(days, ChronoUnit.DAYS));
        List<DateValueResult> results = serverRepository.aggregateHistoricalMetric(metric, startDate);

        return Map.of(
            "success", true,
            "data", Map.of(
                "metric", metric,
                "range", range != null && !range.isBlank() ? range : "30d",
                "data", results
            )
        );
    }

    public @NotNull Object exportAnalytics(@Nullable String type, @Nullable String range) {
        String normalizedType = type != null ? type : "json";
        String normalizedRange = range != null && !range.isBlank() ? range : "30d";

        if ("csv".equals(normalizedType)) {
            return "Date,Servers,Users,Tickets\n2024-01-01,100,1500,820";
        }

        return Map.of(
            "exportDate", new Date().toString(),
            "range", normalizedRange,
            "data", Map.of("servers", 100, "users", 1500, "tickets", 820)
        );
    }
}
