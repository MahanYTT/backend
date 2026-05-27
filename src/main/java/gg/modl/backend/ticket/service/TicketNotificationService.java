package gg.modl.backend.ticket.service;

import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailHTMLTemplate;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.infrastructure.config.ModlProperties;
import gg.modl.backend.infrastructure.util.UuidUtil;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketNotificationService {
    private final PlayerMongoRepository playerRepository;
    private final EmailService emailService;
    private final ModlProperties modlProperties;

    @Async
    public void notifyTicketReply(Server server, Ticket ticket, TicketReply reply) {
        if (!reply.isStaff()) {
            return;
        }

        String creatorEmail = getCreatorEmail(ticket);
        String creatorUuid = ticket.getCreatorUuid();

        if (creatorEmail != null && !creatorEmail.isBlank()) {
            sendEmailNotification(server, ticket, reply, creatorEmail);
        }

        if (creatorUuid != null && !creatorUuid.isBlank()) {
            createInGameNotification(server, ticket, reply, creatorUuid);
        }
    }

    private void sendEmailNotification(Server server, Ticket ticket, TicketReply reply, String toEmail) {
        try {
            String serverName = HtmlUtils.htmlEscape(server.getServerName() != null ? server.getServerName() : "Server");
            String playerName = HtmlUtils.htmlEscape(ticket.getCreatorName() != null ? ticket.getCreatorName() : "Player");
            String ticketType = HtmlUtils.htmlEscape(resolveTicketLabel(ticket));
            String ticketId = HtmlUtils.htmlEscape(ticket.getId());
            String ticketSubject = HtmlUtils.htmlEscape(ticket.getSubject() != null ? ticket.getSubject() : "No Subject");
            String replyAuthor = HtmlUtils.htmlEscape(reply.getName() != null ? reply.getName() : "Staff");
            String replyContent = HtmlUtils.htmlEscape(reply.getContent() != null ? reply.getContent() : "");
            String ticketUrl = buildTicketUrl(server, ticketId);

            EmailHTMLTemplate.HTMLEmail email = EmailHTMLTemplate.TICKET_REPLY_TEMPLATE.build(
                serverName,
                playerName,
                true,
                ticketType,
                ticketId,
                ticketSubject,
                replyAuthor,
                replyContent,
                ticketUrl
            );

            emailService.send(toEmail, email);
        } catch (Exception exception) {
            log.error("Failed to send ticket reply email to {} for ticket {}: {}",
                toEmail, ticket.getId(), exception.getMessage());
        }
    }

    private String buildTicketUrl(Server server, String ticketId) {
        String domain = server.getCustomDomainOverride();
        if (domain == null || domain.isBlank()) {
            domain = server.getServerName() + "." + modlProperties.getDomain();
        }
        return "https://" + domain + "/ticket/" + ticketId;
    }

    private String resolveTicketLabel(Ticket ticket) {
        TicketCategory category = ticket.getType();
        return category != null ? category.getDisplayName() : TicketCategory.SUPPORT.getDisplayName();
    }

    private void createInGameNotification(Server server, Ticket ticket, TicketReply reply, String playerUuid) {
        try {
            Player player = findPlayer(server, playerUuid);
            if (player == null) {
                log.debug("Player with UUID {} not found, skipping in-game notification", playerUuid);
                return;
            }

            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("id", UUID.randomUUID().toString());
            notification.put("type", "TICKET_REPLY");
            notification.put("message", buildNotificationMessage(ticket, reply));
            notification.put("timestamp", System.currentTimeMillis());

            Map<String, Object> notificationData = new LinkedHashMap<>();
            notificationData.put("ticketId", ticket.getId());
            notificationData.put("ticketUrl", buildTicketUrl(server, ticket.getId()));
            notificationData.put("replyAuthor", reply.getName());
            notification.put("data", notificationData);

            appendNotification(server, player, notification);
        } catch (Exception exception) {
            log.error("Failed to create in-game notification for player {} for ticket {}: {}",
                playerUuid, ticket.getId(), exception.getMessage());
        }
    }

    private Player findPlayer(Server server, String playerUuid) {
        return playerRepository.findByMinecraftUuid(server, UuidUtil.normalizeUuid(playerUuid)).orElse(null);
    }

    private void appendNotification(Server server, Player player, Map<String, Object> notification) {
        Map<String, Object> data = player.getData();
        if (data == null) {
            data = new LinkedHashMap<>();
            player.setData(data);
        }

        Object rawPendingNotifications = data.get("pendingNotifications");
        List<Object> pendingNotifications = rawPendingNotifications instanceof List<?> existing
                                            ? new ArrayList<>(existing)
                                            : new ArrayList<>();
        pendingNotifications.add(notification);
        data.put("pendingNotifications", pendingNotifications);
        playerRepository.saveEntity(server, player);
    }

    private String buildNotificationMessage(Ticket ticket, TicketReply reply) {
        String replyAuthor = reply.getName() != null ? reply.getName() : "Staff";
        return String.format("%s replied to your ticket #%s", replyAuthor, ticket.getId());
    }

    private String getCreatorEmail(Ticket ticket) {
        if (ticket.getData() == null) {
            return null;
        }
        Object email = ticket.getData().get("creatorEmail");
        if (email == null) {
            return null;
        }

        String normalizedEmail = EmailAddressUtil.normalizeIfValid(email.toString());
        if (normalizedEmail == null) {
            log.warn("Skipping ticket email notification for {} due to invalid creator email: {}",
                ticket.getId(),
                email);
            return null;
        }

        return normalizedEmail;
    }

    @Async
    public void notifyTicketClosed(Server server, Ticket ticket) {
        String creatorEmail = getCreatorEmail(ticket);
        String creatorUuid = ticket.getCreatorUuid();

        if (creatorEmail != null && !creatorEmail.isBlank()) {
            sendTranscriptEmail(server, ticket, creatorEmail);
        }

        if (creatorUuid != null && !creatorUuid.isBlank()) {
            createClosedInGameNotification(server, ticket, creatorUuid);
        }
    }

    private void sendTranscriptEmail(Server server, Ticket ticket, String toEmail) {
        try {
            String serverName = HtmlUtils.htmlEscape(server.getServerName() != null ? server.getServerName() : "Server");
            String playerName = HtmlUtils.htmlEscape(ticket.getCreatorName() != null ? ticket.getCreatorName() : "Player");
            String ticketType = HtmlUtils.htmlEscape(resolveTicketLabel(ticket));
            String ticketId = HtmlUtils.htmlEscape(ticket.getId());
            String ticketSubject = HtmlUtils.htmlEscape(ticket.getSubject() != null ? ticket.getSubject() : "No Subject");
            String ticketUrl = buildTicketUrl(server, ticketId);

            StringBuilder messagesHtml = new StringBuilder();
            if (ticket.getReplies() != null) {
                for (TicketReply reply : ticket.getReplies()) {
                    String author = HtmlUtils.htmlEscape(reply.getName() != null ? reply.getName() : (reply.isStaff() ? "Staff" : "Player"));
                    String date = HtmlUtils.htmlEscape(reply.getCreated() != null ? reply.getCreated().toString() : "");
                    String content = HtmlUtils.htmlEscape(reply.getContent() != null ? reply.getContent() : "").replace("\n", "<br>");
                    String roleLabel = reply.isStaff() ? " (Staff)" : "";

                    messagesHtml.append("""
                        <div style=\"border: 1px solid #e9ecef; border-radius: 4px; padding: 12px; margin: 10px 0;\">
                          <div style=\"margin-bottom: 8px;\">
                            <strong style=\"color: #333;\">%s%s</strong>
                            <span style=\"color: #888; font-size: 12px; margin-left: 8px;\">%s</span>
                          </div>
                          <div style=\"color: #555; font-size: 14px;\">%s</div>
                        </div>
                        """.formatted(author, roleLabel, date, content));
                }
            }

            EmailHTMLTemplate.HTMLEmail email = EmailHTMLTemplate.TICKET_TRANSCRIPT_TEMPLATE.build(
                serverName, playerName, ticketType, ticketId, ticketSubject, messagesHtml.toString(), ticketUrl
            );

            emailService.send(toEmail, email);
        } catch (Exception exception) {
            log.error("Failed to send ticket transcript email to {} for ticket {}: {}",
                toEmail, ticket.getId(), exception.getMessage());
        }
    }

    private void createClosedInGameNotification(Server server, Ticket ticket, String playerUuid) {
        try {
            Player player = findPlayer(server, playerUuid);
            if (player == null) {
                return;
            }

            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("id", UUID.randomUUID().toString());
            notification.put("type", "TICKET_CLOSED");
            notification.put("message", String.format("Your ticket #%s has been closed", ticket.getId()));
            notification.put("timestamp", System.currentTimeMillis());

            Map<String, Object> notificationData = new LinkedHashMap<>();
            notificationData.put("ticketId", ticket.getId());
            notificationData.put("ticketUrl", buildTicketUrl(server, ticket.getId()));
            notification.put("data", notificationData);

            appendNotification(server, player, notification);
        } catch (Exception exception) {
            log.error("Failed to create in-game closed notification for player {} for ticket {}: {}",
                playerUuid, ticket.getId(), exception.getMessage());
        }
    }

}
