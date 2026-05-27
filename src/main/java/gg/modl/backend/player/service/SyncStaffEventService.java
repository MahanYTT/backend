package gg.modl.backend.player.service;

import gg.modl.backend.database.mongo.repository.PunishmentMongoRepository;
import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.infrastructure.config.ModlProperties;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.proto.modl.v1.SyncStaffNotification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class SyncStaffEventService {
    private final TicketMongoRepository ticketRepository;
    private final PunishmentMongoRepository punishmentRepository;
    private final IssuerNameResolver issuerNameResolver;
    private final ModlProperties modlProperties;

    public List<SyncStaffNotification> collectStaffEvents(
        Server server,
        Instant lastSync,
        List<PunishmentType> types
    ) {
        List<SyncStaffNotification> notifications = new ArrayList<>();
        collectTicketNotifications(server, lastSync, notifications);
        collectPunishmentNotifications(server, lastSync, types, notifications);
        return notifications;
    }

    private void collectTicketNotifications(Server server, Instant lastSync, List<SyncStaffNotification> notifications) {
        try {
            List<Ticket> recentTickets = ticketRepository.findCreatedAfterExcludingUnfinished(server, Date.from(lastSync), 20);

            for (Ticket ticket : recentTickets) {
                TicketCategory ticketType = ticket.getType();
                if (ticketType == TicketCategory.APPLICATION) {
                    continue;
                }

                String creatorName = ticket.getCreatorName() != null ? ticket.getCreatorName() : "Unknown";
                String createdServer = null;
                if (ticket.getData() != null) {
                    Object value = ticket.getData().get("createdServer");
                    if (value instanceof String serverValue && !serverValue.isBlank()) {
                        createdServer = serverValue;
                    }
                }

                String message;
                if (ticketType != null && ticketType.isReport()) {
                    String reportedPlayer = ticket.getReportedPlayer() != null ? ticket.getReportedPlayer() : "Unknown";
                    String categoryLabel = ticketType == TicketCategory.CHAT ? "Chat" : "Gameplay";
                    message = creatorName + ": reported " + reportedPlayer;
                    if (createdServer != null) {
                        message += " on " + createdServer;
                    }
                    message += " (" + categoryLabel + ")";
                } else {
                    message = creatorName + ": created " + ticket.getId();
                    if (createdServer != null) {
                        message += " on " + createdServer;
                    }
                }

                Map<String, Object> ticketData = new LinkedHashMap<>();
                ticketData.put("ticketId", ticket.getId());
                ticketData.put("creatorName", creatorName);
                ticketData.put("subject", ticket.getSubject() != null ? ticket.getSubject() : "");

                String firstReply = "";
                if (ticket.getReplies() != null && !ticket.getReplies().isEmpty()) {
                    String content = ticket.getReplies().get(0).getContent();
                    if (content != null) {
                        firstReply = content.replace("**", "").replace("```", "");
                    }
                }
                ticketData.put("firstReplyContent", firstReply);

                String domain = server.getCustomDomainOverride();
                if (domain == null || domain.isBlank()) {
                    domain = server.getCustomDomain() + "." + modlProperties.getDomain();
                }
                ticketData.put("ticketUrl", "https://" + domain + "/ticket/" + ticket.getId());
                ticketData.put("ticketType", ticketType != null ? ticketType.getId() : "");
                if (ticketType != null && ticketType.isReport()) {
                    ticketData.put("reportedPlayer", ticket.getReportedPlayer() != null ? ticket.getReportedPlayer() : "");
                    ticketData.put("category", ticketType.getId());
                }

                long timestamp = ticket.getCreated() != null ? ticket.getCreated().getTime() : System.currentTimeMillis();
                notifications.add(SyncStaffNotification.newBuilder()
                    .setId("ticket_" + ticket.getId())
                    .setType("TICKET_CREATED")
                    .setMessage(message)
                    .setTimestamp(timestamp)
                    .setData(StructMapper.toStruct(ticketData))
                    .build());
            }
        } catch (Exception e) {
            log.warn("Failed to collect ticket notifications during sync", e);
        }
    }

    private void collectPunishmentNotifications(
        Server server,
        Instant lastSync,
        List<PunishmentType> types,
        List<SyncStaffNotification> notifications
    ) {
        try {
            List<Player> playersWithNewPunishments = punishmentRepository.findWithPunishmentsIssuedAfter(server, Date.from(lastSync), 50);
            Map<Integer, PunishmentType> typesByOrdinal = PunishmentTypeIndex.byOrdinal(types);

            Set<String> issuerIds = new HashSet<>();
            for (Player player : playersWithNewPunishments) {
                for (Punishment punishment : player.getPunishments()) {
                    if (punishment.getIssuerId() != null) {
                        issuerIds.add(punishment.getIssuerId());
                    }
                }
            }
            Map<String, String> resolvedIssuers = issuerIds.isEmpty()
                                                  ? Map.of()
                                                  : issuerNameResolver.batchResolve(issuerIds, server);

            for (Player player : playersWithNewPunishments) {
                String playerName = PlayerDataUtils.extractLatestUsername(player.getUsernames());

                for (Punishment punishment : player.getPunishments()) {
                    if (punishment.getIssued() == null || !punishment.getIssued().toInstant().isAfter(lastSync)) {
                        continue;
                    }
                    PunishmentType punishmentType = typesByOrdinal.get(punishment.getTypeOrdinal());
                    String typeName = punishmentType != null ? punishmentType.getName() : "Unknown";
                    String action = punishmentType != null && punishmentType.isBan() ? "banned"
                                  : punishmentType != null && punishmentType.isMute() ? "muted"
                                  : punishmentType != null && punishmentType.isKick() ? "kicked"
                                  : "punished";

                    String issuerName = issuerNameResolver.resolve(punishment.getIssuerId(), punishment.getIssuerName(), resolvedIssuers);
                    notifications.add(SyncStaffNotification.newBuilder()
                        .setId("punishment_" + punishment.getId())
                        .setType("PUNISHMENT_ISSUED")
                        .setMessage(issuerName + ": " + action + " " + playerName + " (" + typeName + ")")
                        .setTimestamp(punishment.getIssued().getTime())
                        .build());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to collect punishment notifications during sync", e);
        }
    }

}
