package gg.modl.backend.analytics.service;

import static gg.modl.backend.infrastructure.util.SafeConvertUtil.toInt;

import gg.modl.backend.analytics.dto.response.AuditLogsAnalyticsResponse;
import gg.modl.backend.analytics.dto.response.OverviewResponse;
import gg.modl.backend.analytics.dto.response.PlayerActivityResponse;
import gg.modl.backend.analytics.dto.response.PunishmentAnalyticsResponse;
import gg.modl.backend.analytics.dto.response.TicketAnalyticsResponse;
import gg.modl.backend.database.mongo.repository.AnalyticsMongoRepository;
import gg.modl.backend.database.mongo.repository.AnalyticsMongoRepository.IdCountResult;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.infrastructure.util.DateRangeUtil;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {
    private static final String ANALYTICS_TIME_ZONE = TimeZone.getDefault().getID();

    private final AnalyticsMongoRepository analyticsRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final IssuerNameResolver issuerNameResolver;
    private final StaffMongoRepository staffRepository;

    @NotNull
    public OverviewResponse getOverview(@NotNull Server server) {
        final Date thirtyDaysAgo = DateRangeUtil.daysAgo(30);
        final Date sixtyDaysAgo = DateRangeUtil.daysAgo(60);
        final AnalyticsMongoRepository.OverviewStats stats = analyticsRepository.loadOverviewStats(server, thirtyDaysAgo, sixtyDaysAgo);

        final int ticketChange = stats.previousTickets() > 0
                                 ? (int) Math.round(((double) (stats.recentTickets() - stats.previousTickets()) / stats.previousTickets()) * 100)
                                 : 0;
        final int playerChange = 0; // TODO: implement or remove

        return new OverviewResponse(
            stats.totalTickets(),
            stats.totalPlayers(),
            stats.totalStaff(),
            stats.activeTickets(),
            ticketChange,
            playerChange
        );
    }

    @NotNull
    public TicketAnalyticsResponse getTicketAnalytics(@NotNull Server server, @NotNull String period) {
        final Date startDate = DateRangeUtil.getStartDate(period);
        final List<IdCountResult> statusResults = analyticsRepository.aggregateTicketStatusCounts(server, startDate);
        final List<TicketAnalyticsResponse.StatusCount> byStatus = statusResults.stream()
            .map(result -> new TicketAnalyticsResponse.StatusCount(normalizeStatus(result.id()), result.count()))
            .toList();

        final List<IdCountResult> categoryResults = analyticsRepository.aggregateTicketCategoryCounts(server, startDate);
        final List<TicketAnalyticsResponse.CategoryCount> byCategory = categoryResults.stream()
            .map(result -> new TicketAnalyticsResponse.CategoryCount(normalizeCategory(result.id()), result.count()))
            .toList();

        final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMM dd");
        final List<TicketAnalyticsResponse.DailyTicket> dailyTickets = analyticsRepository.aggregateDailyTicketCounts(server, startDate)
            .stream()
            .map(result -> new TicketAnalyticsResponse.DailyTicket(
                formatDateLabel(result.id(), dateFormatter),
                result.count()
            ))
            .toList();

        final List<TicketAnalyticsResponse.CategoryResolutionTime> avgResolution = Collections.emptyList();

        return new TicketAnalyticsResponse(byStatus, byCategory, avgResolution, dailyTickets);
    }

    private String formatDateLabel(String dateKey, DateTimeFormatter formatter) {
        if (dateKey == null || dateKey.isBlank()) {
            return "Unknown";
        }

        try {
            return LocalDate.parse(dateKey).format(formatter);
        } catch (DateTimeParseException e) {
            log.debug("Unable to parse analytics date key '{}'; passing through raw value", dateKey);
            return dateKey;
        }
    }

    private String normalizeCategory(String category) {
        try {
            return TicketCategory.fromCanonicalId(category).getDisplayName();
        } catch (IllegalArgumentException e) {
            log.debug("Unknown ticket category id '{}', bucketing as 'Other'", category);
            return "Other";
        }
    }

    private String normalizeStatus(String status) {
        try {
            return TicketStatus.fromCanonicalId(status).getDisplayName();
        } catch (IllegalArgumentException e) {
            log.debug("Unknown ticket status id '{}', bucketing as 'Other'", status);
            return "Other";
        }
    }

    public PunishmentAnalyticsResponse getPunishmentAnalytics(Server server, String period) {
        final Date startDate = DateRangeUtil.getStartDate(period);
        final Document facetResults = analyticsRepository.aggregatePunishmentAnalytics(server, startDate, ANALYTICS_TIME_ZONE);
        if (facetResults == null) {
            return new PunishmentAnalyticsResponse(List.of(), List.of(), List.of(), List.of());
        }
        final Map<Integer, String> punishmentTypeNames = resolvePunishmentTypeNames(server);

        final List<PunishmentAnalyticsResponse.TypeCount> byType = toDocumentList(facetResults.get("byType")).stream()
            .map(doc -> {
                Object rawTypeOrdinal = doc.get("_id");
                Integer typeOrdinal = rawTypeOrdinal instanceof Number number ? number.intValue() : null;
                String typeName = typeOrdinal != null
                                  ? punishmentTypeNames.getOrDefault(typeOrdinal, "Unknown")
                                  : "Unknown";
                return new PunishmentAnalyticsResponse.TypeCount(typeName, toInt(doc.get("count")));
            })
            .sorted((a, b) -> Integer.compare(b.count(), a.count()))
            .toList();

        final List<Document> byStaffDocs = toDocumentList(facetResults.get("byStaff"));
        final Set<String> potentialIds = new HashSet<>();
        for (Document document : byStaffDocs) {
            Object rawId = document.get("_id");
            if (rawId instanceof String s && !s.isBlank()) {
                potentialIds.add(s);
            }
        }
        final Map<String, String> resolvedStaff = issuerNameResolver.batchResolve(potentialIds, server);

        final Map<String, Integer> staffCountMap = new HashMap<>();
        for (Document document : byStaffDocs) {
            Object rawId = document.get("_id");
            String staffName;
            if (rawId instanceof String s && resolvedStaff.containsKey(s)) {
                staffName = resolvedStaff.get(s);
            } else {
                staffName = normalizeStaffName(rawId);
            }
            staffCountMap.merge(staffName, toInt(document.get("count")), Integer::sum);
        }
        final List<PunishmentAnalyticsResponse.StaffPunishment> byStaff = staffCountMap.entrySet()
            .stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(20)
            .map(entry -> new PunishmentAnalyticsResponse.StaffPunishment(entry.getKey(), entry.getValue()))
            .toList();

        final Map<String, Integer> dailyPunishmentMap = new LinkedHashMap<>();
        for (Document document : toDocumentList(facetResults.get("daily"))) {
            final String dayLabel = formatPunishmentDay(document.getString("_id"));
            dailyPunishmentMap.merge(dayLabel, toInt(document.get("count")), Integer::sum);
        }
        final List<PunishmentAnalyticsResponse.DailyPunishment> dailyPunishments = dailyPunishmentMap.entrySet()
            .stream()
            .map(entry -> new PunishmentAnalyticsResponse.DailyPunishment(entry.getKey(), entry.getValue()))
            .toList();

        return new PunishmentAnalyticsResponse(byType, List.of(), dailyPunishments, byStaff);
    }

    private Map<Integer, String> resolvePunishmentTypeNames(Server server) {
        Map<Integer, String> typeNames = new HashMap<>();
        punishmentTypeService.getPunishmentTypes(server).forEach(type ->
            typeNames.put(type.getOrdinal(), type.getName())
        );
        return typeNames;
    }

    private List<Document> toDocumentList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }

        List<Document> documents = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Document document) {
                documents.add(document);
            }
        }
        return documents;
    }

    private String normalizeStaffName(Object rawStaffName) {
        if (rawStaffName == null) {
            return "Unknown";
        }

        String normalized = rawStaffName.toString().trim();
        return normalized.isBlank() ? "Unknown" : normalized;
    }

    private static final DateTimeFormatter SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd");

    private String formatPunishmentDay(String dateKey) {
        return formatDateLabel(dateKey, SHORT_DATE_FORMATTER);
    }

    public AuditLogsAnalyticsResponse getAuditLogsAnalytics(Server server, String period) {
        Date startDate = DateRangeUtil.getStartDate(period);
        List<IdCountResult> levelResults = analyticsRepository.aggregateAuditLogLevelCounts(server, startDate);
        List<AuditLogsAnalyticsResponse.LevelCount> byLevel = levelResults.stream()
            .map(result -> new AuditLogsAnalyticsResponse.LevelCount(
                result.id() != null ? result.id() : "unknown",
                result.count()
            ))
            .toList();

        final long now = System.currentTimeMillis();
        final Date since = DateRangeUtil.daysAgo(1);

        final List<Document> hourlyResults = analyticsRepository.aggregateHourlyAuditLogCounts(server, since, ANALYTICS_TIME_ZONE);

        final Map<String, Integer> hourlyMap = new LinkedHashMap<>();
        for (Document doc : hourlyResults) {
            final String bucketKey = doc.getString("_id");
            final int count = toInt(doc.get("count"));
            try {
                LocalDateTime ldt = LocalDateTime.parse(bucketKey, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH"));
                String hourLabel = String.format("%02d:00", ldt.getHour());
                hourlyMap.merge(hourLabel, count, Integer::sum);
            } catch (DateTimeParseException e) {
                log.debug("Skipping audit log hourly bucket with unparseable key '{}'", bucketKey);
            }
        }

        ZoneId zone = ZoneId.of(ANALYTICS_TIME_ZONE);
        List<AuditLogsAnalyticsResponse.HourlyCount> hourlyTrend = new ArrayList<>();
        for (int i = 23; i >= 0; i--) {
            Instant hourEnd = Instant.ofEpochMilli(now - (long) i * 60 * 60 * 1000);
            int hour = hourEnd.atZone(zone).getHour();
            String hourLabel = String.format("%02d:00", hour);
            int count = hourlyMap.getOrDefault(hourLabel, 0);
            hourlyTrend.add(new AuditLogsAnalyticsResponse.HourlyCount(hourLabel, count));
        }

        return new AuditLogsAnalyticsResponse(byLevel, hourlyTrend);
    }

    public PlayerActivityResponse getPlayerActivityAnalytics(Server server, String period) {
        final Date startDate = DateRangeUtil.getStartDate(period);

        final Document facetResults = analyticsRepository.aggregatePlayerActivity(server, startDate, ANALYTICS_TIME_ZONE);
        if (facetResults == null) {
            return new PlayerActivityResponse(List.of(), List.of(),
                new PlayerActivityResponse.SuspiciousActivity(0, 0));
        }

        final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMM dd");

        final List<PlayerActivityResponse.DailyCount> newPlayersTrend = toDocumentList(facetResults.get("newPlayers")).stream()
            .map(doc -> new PlayerActivityResponse.DailyCount(
                formatDateLabel(doc.getString("_id"), dateFormatter),
                toInt(doc.get("count"))))
            .toList();

        final List<PlayerActivityResponse.CountryCount> loginsByCountry = toDocumentList(facetResults.get("byCountry")).stream()
            .map(doc -> new PlayerActivityResponse.CountryCount(
                doc.getString("_id"),
                toInt(doc.get("count"))))
            .toList();

        final List<Document> suspiciousList = toDocumentList(facetResults.get("suspicious"));
        int proxyCount = 0;
        int hostingCount = 0;
        if (!suspiciousList.isEmpty()) {
            final Document suspicious = suspiciousList.getFirst();
            proxyCount = toInt(suspicious.get("proxyCount"));
            hostingCount = toInt(suspicious.get("hostingCount"));
        }

        return new PlayerActivityResponse(
            newPlayersTrend,
            loginsByCountry,
            new PlayerActivityResponse.SuspiciousActivity(proxyCount, hostingCount)
        );
    }

}
