package gg.modl.backend.admin.service;

import gg.modl.backend.admin.data.SystemLog;
import gg.modl.backend.admin.dto.request.CreateSystemLogRequest;
import gg.modl.backend.admin.dto.request.ResolveLogRequest;
import gg.modl.backend.database.mongo.repository.GlobalMongoAdminRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository.MonitoringServerStats;
import gg.modl.backend.database.mongo.repository.SystemLogMongoRepository;
import gg.modl.backend.database.mongo.repository.SystemLogMongoRepository.MonitoringLogStats;
import gg.modl.backend.infrastructure.util.DateRangeUtil;
import gg.modl.backend.infrastructure.util.PaginationHelper;
import gg.modl.backend.server.data.ProvisioningStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminMonitoringService {
    private final SystemLogMongoRepository systemLogRepository;
    private final ServerMongoRepository serverRepository;
    private final GlobalMongoAdminRepository globalMongoAdminRepository;

    public Map<String, Object> getDashboard() {
        Date oneDayAgo = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
        Date oneWeekAgo = Date.from(Instant.now().minus(7, ChronoUnit.DAYS));
        Date fiveMinutesAgo = Date.from(Instant.now().minus(5, ChronoUnit.MINUTES));

        MonitoringServerStats serverStats = serverRepository.aggregateMonitoringServerStats(fiveMinutesAgo, oneWeekAgo);
        MonitoringLogStats logStats = systemLogRepository.aggregateMonitoringLogStats(oneDayAgo);

        int healthScore = calculateHealthScore(
            serverStats.total(),
            serverStats.active(),
            serverStats.failed(),
            logStats.critical24h(),
            logStats.error24h(),
            logStats.unresolvedCritical(),
            logStats.unresolvedError()
        );
        String healthStatus = healthScore >= 95 ? "excellent"
                                                : healthScore >= 85 ? "good"
                                                                    : healthScore >= 70 ? "fair"
                                                                                        : "poor";

        return Map.of(
            "success", true,
            "data", Map.of(
                "servers", Map.of(
                    "total", serverStats.total(),
                    "active", serverStats.active(),
                    "pending", serverStats.pending(),
                    "failed", serverStats.failed(),
                    "recentRegistrations", serverStats.recentRegistrations(),
                    "concurrentServers", serverStats.concurrent(),
                    "concurrentPlayers", serverStats.concurrentPlayers()
                ),
                "logs", Map.of(
                    "last24h", Map.of(
                        "total", logStats.total24h(),
                        "critical", logStats.critical24h(),
                        "error", logStats.error24h(),
                        "warning", logStats.warning24h()
                    ),
                    "unresolved", Map.of(
                        "critical", logStats.unresolvedCritical(),
                        "error", logStats.unresolvedError()
                    )
                ),
                "systemHealth", Map.of("score", healthScore, "status", healthStatus),
                "trends", systemLogRepository.findLogTrends(oneWeekAgo),
                "lastUpdated", new Date()
            )
        );
    }

    private int calculateHealthScore(long total, long active, long failed, long critical, long errors, long unresolvedCritical, long unresolvedErrors) {
        int score = 100;
        if (total > 0) {
            score -= (int) ((failed / (double) total) * 30);
        }
        score -= (int) Math.min(critical * 5, 25);
        score -= (int) Math.min(errors, 20);
        score -= (int) (unresolvedCritical * 10);
        score -= (int) (unresolvedErrors * 3);
        return Math.max(0, score);
    }

    public Map<String, Object> getLogs(
        int page,
        int limit,
        String level,
        String source,
        String serverId,
        String category,
        String resolved,
        String search,
        String startDate,
        String endDate,
        String sort,
        String order
    ) {
        int pageNum = PaginationHelper.normalizePage(page);
        int limitNum = PaginationHelper.normalizeLimit(limit, 100);
        int skip = PaginationHelper.calculateSkip(page, limitNum);
        Date start = DateRangeUtil.parseEpochMillis(startDate);
        Date end = DateRangeUtil.parseEpochMillis(endDate);

        List<SystemLog> logs = systemLogRepository.findLogs(
            level,
            source,
            serverId,
            category,
            resolved,
            search,
            start,
            end,
            sort,
            order,
            skip,
            limitNum
        );
        long total = systemLogRepository.countLogs(level, source, serverId, category, resolved, search, start, end);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("level", level);
        filters.put("source", source);
        filters.put("serverId", serverId);
        filters.put("category", category);
        filters.put("resolved", resolved);
        filters.put("search", search);

        return Map.of(
            "success", true,
            "data", Map.of(
                "logs", logs,
                "pagination", Map.of(
                    "page", pageNum,
                    "limit", limitNum,
                    "total", total,
                    "pages", PaginationHelper.calculateTotalPages(total, limitNum)
                ),
                "filters", filters
            )
        );
    }

    public @NotNull SystemLog createLog(@NotNull CreateSystemLogRequest request) {
        SystemLog logData = request.toSystemLog();
        logData.setTimestamp(new Date());
        return systemLogRepository.saveEntity(logData);
    }

    public Map<String, Object> getSources() {
        List<String> sources = systemLogRepository.findDistinctSources();
        List<String> categories = systemLogRepository.findDistinctCategories();

        sources.removeIf(Objects::isNull);
        categories.removeIf(Objects::isNull);

        return Map.of(
            "success", true,
            "data", Map.of(
                "sources", sources,
                "categories", categories
            )
        );
    }

    public @NotNull Optional<SystemLog> resolveLog(@NotNull String id, @NotNull ResolveLogRequest request) {
        return Optional.ofNullable(systemLogRepository.resolveById(
            id,
            request.resolvedBy() != null ? request.resolvedBy() : "admin",
            new Date()
        ));
    }

    public Map<String, Object> getHealth() {
        List<Map<String, Object>> checks = new ArrayList<>();
        String overallStatus = "healthy";

        try {
            long start = System.currentTimeMillis();
            globalMongoAdminRepository.ping();
            long responseTime = System.currentTimeMillis() - start;
            checks.add(Map.of(
                "name", "Database Connectivity",
                "status", "healthy",
                "message", "MongoDB connection is responsive.",
                "responseTime", responseTime
            ));
        } catch (Exception exception) {
            checks.add(Map.of(
                "name", "Database Connectivity",
                "status", "critical",
                "message", "Failed to ping MongoDB.",
                "error", exception.getMessage()
            ));
            overallStatus = "critical";
        }

        long criticalCount = systemLogRepository.countUnresolvedByLevelSince(
            "critical",
            Date.from(Instant.now().minus(1, ChronoUnit.DAYS))
        );
        String logStatus = criticalCount > 5 ? "critical" : criticalCount > 0 ? "degraded" : "healthy";
        checks.add(Map.of(
            "name", "Critical System Logs",
            "status", logStatus,
            "message", criticalCount + " unresolved critical log(s) in the last 24 hours.",
            "count", criticalCount
        ));
        if ("critical".equals(logStatus)) {
            overallStatus = "critical";
        } else if ("degraded".equals(logStatus) && !"critical".equals(overallStatus)) {
            overallStatus = "degraded";
        }

        long failedCount = serverRepository.countByProvisioningStatus(ProvisioningStatus.FAILED);
        String serverStatus = failedCount > 0 ? "degraded" : "healthy";
        checks.add(Map.of(
            "name", "Server Provisioning",
            "status", serverStatus,
            "message", failedCount + " server(s) failed to provision.",
            "count", failedCount
        ));
        if ("degraded".equals(serverStatus) && !"critical".equals(overallStatus)) {
            overallStatus = "degraded";
        }

        return Map.of(
            "success", true,
            "data", Map.of(
                "status", overallStatus,
                "checks", checks,
                "timestamp", new Date()
            )
        );
    }

    public long deleteLogs(@NotNull List<String> logIds) {
        return systemLogRepository.deleteByIds(logIds);
    }

    public String exportLogs(
        String level,
        String source,
        String category,
        String resolved,
        String search,
        String startDate,
        String endDate
    ) {
        List<SystemLog> logs = systemLogRepository.findLogsForExport(
            DateRangeUtil.normalizeAllFilter(level),
            DateRangeUtil.normalizeAllFilter(source),
            null,
            DateRangeUtil.normalizeAllFilter(category),
            DateRangeUtil.normalizeAllFilter(resolved),
            search,
            DateRangeUtil.parseEpochMillis(startDate),
            DateRangeUtil.parseEpochMillis(endDate),
            10000
        );

        StringBuilder csv = new StringBuilder("Timestamp,Level,Source,Category,Message,Resolved,Resolved By\n");
        for (SystemLog logEntry : logs) {
            csv.append("\"").append(logEntry.getTimestamp()).append("\",");
            csv.append("\"").append(logEntry.getLevel() != null ? logEntry.getLevel() : "").append("\",");
            csv.append("\"").append(logEntry.getSource() != null ? logEntry.getSource() : "").append("\",");
            csv.append("\"").append(logEntry.getCategory() != null ? logEntry.getCategory() : "").append("\",");
            csv.append("\"").append(logEntry.getMessage() != null ? logEntry.getMessage().replace("\"", "\"\"") : "").append("\",");
            csv.append("\"").append(logEntry.isResolved() ? "Yes" : "No").append("\",");
            csv.append("\"").append(logEntry.getResolvedBy() != null ? logEntry.getResolvedBy() : "").append("\"\n");
        }
        return csv.toString();
    }

    public long clearAllLogs() {
        long deletedCount = systemLogRepository.deleteAllLogs();
        log.info("All system logs cleared by admin");
        return deletedCount;
    }
}
