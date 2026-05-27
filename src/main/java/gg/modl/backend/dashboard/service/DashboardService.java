package gg.modl.backend.dashboard.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.modl.backend.dashboard.dto.response.ActivityItemResponse;
import gg.modl.backend.dashboard.dto.response.DashboardMetricsResponse;
import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.backend.dashboard.dto.response.RecentPunishmentResponse;
import gg.modl.backend.dashboard.dto.response.RecentTicketResponse;
import gg.modl.backend.database.mongo.fields.PlayerFields;
import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.PunishmentMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.service.PlayerStatusCalculator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.util.TicketAssigneeUtil;
import gg.modl.backend.infrastructure.util.DateRangeUtil;
import gg.modl.backend.player.service.PlayerDataUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {
    private final TicketMongoRepository ticketRepository;
    private final PlayerMongoRepository playerRepository;
    private final PunishmentMongoRepository punishmentRepository;
    private final StaffMongoRepository staffRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final PlayerStatusCalculator statusCalculator;

    private final Cache<String, ActivePunishmentCounts> activePunishmentCountsCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(60))
        .maximumSize(500)
        .build();

    private static final int RECENT_PUNISHMENT_WINDOW_DAYS = 7;
    private static final int MAX_RECENT_TICKETS_LIMIT = 20;
    private static final int MAX_RECENT_PUNISHMENTS_LIMIT = 20;
    private static final int MAX_ACTIVITY_LIMIT = 100;
    private static final int MAX_DAYS = 90;
    private static final int MAX_QUERY_RESULTS = 200;

    public MinecraftDashboardStatsResponse getMinecraftStats(Server server) {
        long unresolvedReports = ticketRepository.countUnresolvedReports(server);
        long unresolvedTickets = ticketRepository.countUnresolvedTickets(server);
        long onlineStaff = countActiveStaff(server);
        long onlinePlayers = playerRepository.countOnlinePlayers(server);
        long totalPlayers = playerRepository.countAll(server);

        ActivePunishmentCounts punishmentCounts = countActivePunishments(server);

        return new MinecraftDashboardStatsResponse(
            unresolvedReports,
            unresolvedTickets,
            onlineStaff,
            onlinePlayers,
            punishmentCounts.bans,
            punishmentCounts.mutes,
            punishmentCounts.total,
            totalPlayers
        );
    }

    private long countActiveStaff(Server server) {
        List<String> assignedUuids = staffRepository.findAssignedMinecraftUuids(server);
        if (assignedUuids.isEmpty()) {
            return 0;
        }
        return playerRepository.countOnlineByUuids(server, assignedUuids);
    }

    private ActivePunishmentCounts countActivePunishments(Server server) {
        return activePunishmentCountsCache.get(server.getId(), key -> computeActivePunishments(server));
    }

    private ActivePunishmentCounts computeActivePunishments(Server server) {
        Map<Integer, PunishmentType> punishmentTypesByOrdinal = buildPunishmentTypeByOrdinal(server);
        long activeBans = 0;
        long activeMutes = 0;
        long totalPunishments = 0;

        for (Player player : punishmentRepository.findWithPunishmentsProjected(server)) {
            if (player.getPunishments().isEmpty()) {
                continue;
            }

            for (Punishment punishment : player.getPunishments()) {
                if (!isPunishmentActiveSafely(punishment)) {
                    continue;
                }

                totalPunishments++;
                PunishmentType punishmentType = punishmentTypesByOrdinal.get(punishment.getTypeOrdinal());
                if (punishmentType == null) {
                    continue;
                }

                if (punishmentType.isBan()) {
                    activeBans++;
                }
                if (punishmentType.isMute()) {
                    activeMutes++;
                }
            }
        }

        return new ActivePunishmentCounts(activeBans, activeMutes, totalPunishments);
    }

    private Map<Integer, PunishmentType> buildPunishmentTypeByOrdinal(Server server) {
        Map<Integer, PunishmentType> punishmentTypes = new LinkedHashMap<>();
        for (PunishmentType punishmentType : punishmentTypeService.getPunishmentTypes(server)) {
            punishmentTypes.put(punishmentType.getOrdinal(), punishmentType);
        }
        return punishmentTypes;
    }

    private boolean isPunishmentActiveSafely(Punishment punishment) {
        try {
            return statusCalculator.isPunishmentActive(punishment);
        } catch (Exception exception) {
            log.warn("Failed to calculate punishment active state for punishment id={}", punishment.getId(), exception);
            return false;
        }
    }

    public DashboardMetricsResponse getMetrics(Server server) {
        Date thirtyDaysAgo = DateRangeUtil.daysAgo(30);
        Date sixtyDaysAgo = DateRangeUtil.daysAgo(60);

        long totalTickets = ticketRepository.countAll(server);
        long openTickets = ticketRepository.countByStatus(server, TicketStatus.OPEN);
        long totalPlayers = playerRepository.countAll(server);
        long totalStaff = staffRepository.countAll(server);

        long totalPunishments = 0;
        long activePunishments = 0;

        long recentTickets = ticketRepository.countCreatedAfter(server, thirtyDaysAgo);
        long prevTickets = ticketRepository.countCreatedBetween(server, sixtyDaysAgo, thirtyDaysAgo);
        int ticketsTrend = prevTickets > 0 ? (int) Math.round(((double) (recentTickets - prevTickets) / prevTickets) * 100) : 0;

        return new DashboardMetricsResponse(
            totalTickets,
            openTickets,
            totalPlayers,
            totalPunishments,
            activePunishments,
            totalStaff,
            ticketsTrend,
            0
        );
    }

    public List<RecentTicketResponse> getRecentTickets(Server server, int limit) {
        int safeLimit = clampLimit(limit, MAX_RECENT_TICKETS_LIMIT);
        List<Ticket> tickets = ticketRepository.findRecentWithProjection(server, safeLimit);

        return tickets.stream()
            .map(ticket -> {
                String initialMessage = null;
                if (ticket.getReplies() != null && !ticket.getReplies().isEmpty()) {
                    TicketReply firstReply = ticket.getReplies().get(0);
                    if (firstReply.getContent() != null) {
                        initialMessage = firstReply.getContent();
                    }
                }

                return new RecentTicketResponse(
                    ticket.getId(),
                    ticket.getSubject() != null ? ticket.getSubject() : "No Subject",
                    initialMessage,
                    ticket.getStatus() != null ? ticket.getStatus().getId() : TicketStatus.OPEN.getId(),
                    ticket.getPriority() != null ? ticket.getPriority().getId() : TicketPriority.NORMAL.getId(),
                    ticket.getCreated(),
                    ticket.getCreatorName() != null ? ticket.getCreatorName() : "Unknown",
                    ticket.getType() != null ? ticket.getType().getId() : TicketCategory.SUPPORT.getId()
                );
            })
            .toList();
    }

    private int clampLimit(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }

    public List<RecentPunishmentResponse> getRecentPunishments(Server server, int limit) {
        int safeLimit = clampLimit(limit, MAX_RECENT_PUNISHMENTS_LIMIT);
        Date cutoff = DateRangeUtil.daysAgo(RECENT_PUNISHMENT_WINDOW_DAYS);

        Map<Integer, String> punishmentTypeNameByOrdinal = buildPunishmentTypeNameByOrdinal(server);
        List<Document> punishmentRows = punishmentRepository.fetchRecentPunishmentRows(server, cutoff, safeLimit);

        List<RecentPunishmentResponse> results = new ArrayList<>();
        for (Document row : punishmentRows) {
            Punishment punishment = readPunishment(server, row.get("punishment", Document.class));
            if (punishment == null || punishment.getIssued() == null || punishment.getIssued().before(cutoff)) {
                continue;
            }

            String reason = "";
            if (punishment.getData() != null && punishment.getData().get("reason") != null) {
                reason = String.valueOf(punishment.getData().get("reason"));
            }

            String typeName = punishmentTypeNameByOrdinal.getOrDefault(punishment.getTypeOrdinal(), "Unknown");
            String playerName = PlayerDataUtils.extractLatestUsername(row.get(PlayerFields.USERNAMES));
            String playerUuid = PlayerDataUtils.extractMinecraftUuid(row);

            results.add(new RecentPunishmentResponse(
                punishment.getId(),
                playerName,
                playerUuid,
                typeName,
                reason,
                punishment.getIssuerName() != null ? punishment.getIssuerName() : "Unknown",
                punishment.getIssued(),
                isPunishmentActiveSafely(punishment)
            ));
        }

        if (results.size() > safeLimit) {
            return results.subList(0, safeLimit);
        }

        return results;
    }

    private Punishment readPunishment(Server server, Document punishmentDocument) {
        if (punishmentDocument == null) {
            return null;
        }

        try {
            return punishmentRepository.readPunishment(server, punishmentDocument);
        } catch (Exception exception) {
            log.warn("Failed to parse punishment document for dashboard response", exception);
            return null;
        }
    }

    private Map<Integer, String> buildPunishmentTypeNameByOrdinal(Server server) {
        Map<Integer, String> names = new HashMap<>();
        buildPunishmentTypeByOrdinal(server).forEach((ordinal, type) ->
            names.put(ordinal, type.getName()));
        return names;
    }

    public List<ActivityItemResponse> getRecentActivity(Server server, String staffEmail, int limit, int days) {
        List<ActivityItemResponse> activities = new ArrayList<>();

        int safeLimit = clampLimit(limit, MAX_ACTIVITY_LIMIT);
        int safeDays = clampLimit(days, MAX_DAYS);

        String staffUsername = staffRepository.findUsernameByEmail(server, staffEmail).orElse(null);
        if (staffUsername == null) {
            return activities;
        }

        Date cutoffDate = DateRangeUtil.daysAgo(safeDays);

        fetchTicketActivities(server, staffUsername, cutoffDate, activities);
        fetchPunishmentActivities(server, staffUsername, cutoffDate, activities);

        activities.sort((left, right) -> right.time().compareTo(left.time()));

        if (activities.size() > safeLimit) {
            return activities.subList(0, safeLimit);
        }

        return activities;
    }

    private void fetchTicketActivities(Server server, String staffUsername, Date cutoffDate, List<ActivityItemResponse> activities) {
        try {
            String normalizedStaffUsername = TicketAssigneeUtil.normalizeSingle(staffUsername);
            List<Ticket> tickets = ticketRepository.findStaffActivityTickets(server, staffUsername, normalizedStaffUsername, cutoffDate, MAX_QUERY_RESULTS);

            for (Ticket ticket : tickets) {
                if (ticket.getCreatorName() != null
                    && ticket.getCreatorName().equals(staffUsername)
                    && ticket.getCreated() != null
                    && ticket.getCreated().after(cutoffDate)) {
                    activities.add(new ActivityItemResponse(
                        "ticket-created-" + ticket.getId(),
                        "new_ticket",
                        "blue",
                        "Created ticket: " + (ticket.getSubject() != null ? ticket.getSubject() : "No Subject"),
                        ticket.getCreated(),
                        "Created " + displayCategory(ticket) + " ticket",
                        List.of(new ActivityItemResponse.ActivityAction("View Ticket", "/panel/tickets/" + ticket.getId(), true))
                    ));
                }

                if (ticket.getReplies() != null) {
                    for (TicketReply reply : ticket.getReplies()) {
                        if (reply.getCreated() == null || !reply.getCreated().after(cutoffDate)) {
                            continue;
                        }

                        boolean isStaffReply = staffUsername.equalsIgnoreCase(reply.getName());
                        String actionType = isStaffReply ? "My reply" : "New reply";
                        String color = isStaffReply ? "green" : "blue";
                        String replyName = reply.getName() != null ? reply.getName() : "Unknown";
                        String description = isStaffReply
                                             ? "You replied to " + displayCategory(ticket) + " ticket"
                                             : replyName + " replied to " + displayCategory(ticket) + " ticket";

                        activities.add(new ActivityItemResponse(
                            "ticket-reply-" + ticket.getId() + "-" + reply.getCreated().getTime(),
                            "mod_action",
                            color,
                            actionType + " on ticket: " + (ticket.getSubject() != null ? ticket.getSubject() : "No Subject"),
                            reply.getCreated(),
                            description,
                            List.of(new ActivityItemResponse.ActivityAction("View Ticket", "/panel/tickets/" + ticket.getId(), true))
                        ));
                    }
                }
            }
        } catch (Exception exception) {
            log.error("Error fetching ticket activities", exception);
        }
    }

    private String displayCategory(Ticket ticket) {
        return ticket.getType() != null ? ticket.getType().getDisplayName() : TicketCategory.SUPPORT.getDisplayName();
    }

    private void fetchPunishmentActivities(Server server, String staffUsername, Date cutoffDate, List<ActivityItemResponse> activities) {
        try {
            Map<Integer, String> punishmentTypeNameByOrdinal = buildPunishmentTypeNameByOrdinal(server);
            List<Document> punishmentRows = punishmentRepository.fetchRecentPunishmentRowsByIssuer(server, staffUsername, cutoffDate, MAX_QUERY_RESULTS);

            for (Document row : punishmentRows) {
                Punishment punishment = readPunishment(server, row.get("punishment", Document.class));
                if (punishment == null || punishment.getIssued() == null || punishment.getIssued().before(cutoffDate)) {
                    continue;
                }

                String username = PlayerDataUtils.extractLatestUsername(row.get(PlayerFields.USERNAMES));
                String playerUuid = PlayerDataUtils.extractMinecraftUuid(row);
                String punishmentTypeName = punishmentTypeNameByOrdinal.getOrDefault(punishment.getTypeOrdinal(), "Unknown");

                activities.add(new ActivityItemResponse(
                    "punishment-" + punishment.getId(),
                    "new_punishment",
                    "red",
                    "Applied " + punishmentTypeName + " to " + username,
                    punishment.getIssued(),
                    "Applied " + punishmentTypeName + " punishment",
                    List.of(new ActivityItemResponse.ActivityAction("View Player", "/panel/players/" + playerUuid, true))
                ));
            }
        } catch (Exception exception) {
            log.error("Error fetching punishment activities", exception);
        }
    }

    private record ActivePunishmentCounts(long bans, long mutes, long total) {}
}
