package gg.modl.backend.ticket.service;

import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.realtime.dispatch.RealtimeEventDispatcher;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.response.SubscriptionUpdateResponse;
import gg.modl.backend.ticket.dto.response.TicketSubscriptionResponse;
import gg.modl.backend.ticket.util.TicketAssigneeUtil;
import gg.modl.proto.modl.v1.AssignedTicketSubscriptionChangedEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.Topic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketSubscriptionService {
    private final StaffMongoRepository staffRepository;
    private final TicketMongoRepository ticketRepository;
    private final RealtimeEventDispatcher realtimeEventDispatcher;
    private static final int MAX_UPDATES_LIMIT = 25;
    private static final int MAX_TICKETS_TO_SCAN = 250;

    public List<TicketSubscriptionResponse> getSubscriptions(Server server, String staffEmail) {
        Staff staff = staffRepository.findByEmailExact(server, staffEmail).orElse(null);
        if (staff == null || staff.getSubscribedTickets() == null || staff.getSubscribedTickets().isEmpty()) {
            return Collections.emptyList();
        }

        List<TicketSubscriptionResponse> subscriptions = new ArrayList<>();
        for (Staff.TicketSubscription subscription : staff.getSubscribedTickets()) {
            if (!subscription.isActive()) {
                continue;
            }

            Ticket ticket = ticketRepository.findByTicketId(server, subscription.getTicketId()).orElse(null);
            if (ticket != null) {
                String title = ticket.getId() + ": " + (ticket.getSubject() != null ? ticket.getSubject() : "Untitled Ticket");
                subscriptions.add(new TicketSubscriptionResponse(
                    subscription.getTicketId(),
                    title,
                    subscription.getSubscribedAt()
                ));
            }
        }

        return subscriptions;
    }

    public boolean unsubscribe(Server server, String staffEmail, String ticketId) {
        boolean removed = staffRepository.deactivateSubscription(server, staffEmail, ticketId);
        if (removed) {
            publishAssignedTicketSubscriptionChanged(server, staffEmail);
        }
        return removed;
    }

    public List<SubscriptionUpdateResponse> getUpdates(Server server, String staffEmail, int limit) {
        int safeLimit = clampLimit(limit);
        Staff staff = staffRepository.findByEmailExact(server, staffEmail).orElse(null);
        if (staff == null || staff.getSubscribedTickets() == null || staff.getSubscribedTickets().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> subscribedTicketIds = staff.getSubscribedTickets()
            .stream()
            .filter(Staff.TicketSubscription::isActive)
            .map(Staff.TicketSubscription::getTicketId)
            .toList();
        if (subscribedTicketIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Ticket> tickets = ticketRepository.findRecentActiveTicketsWithRepliesByIds(
            server,
            subscribedTicketIds,
            ticketScanLimit(safeLimit)
        );

        List<SubscriptionUpdateResponse> updates = new ArrayList<>();
        for (Ticket ticket : tickets) {
            Staff.TicketSubscription subscription = staff.getSubscribedTickets()
                .stream()
                .filter(sub -> sub.getTicketId().equals(ticket.getId()))
                .findFirst()
                .orElse(null);

            if (subscription == null || ticket.getReplies() == null) {
                continue;
            }

            List<TicketReply> recentReplies = ticket.getReplies()
                .stream()
                .filter(reply -> reply.getCreated() != null && reply.getCreated().after(subscription.getSubscribedAt()))
                .sorted((a, b) -> b.getCreated().compareTo(a.getCreated()))
                .toList();

            List<TicketReply> unreadReplies = recentReplies.stream()
                .filter(reply -> subscription.getLastReadAt() == null || reply.getCreated().after(subscription.getLastReadAt()))
                .toList();

            if (!unreadReplies.isEmpty()) {
                TicketReply latestReply = unreadReplies.get(0);
                String ticketTitle = ticket.getId() + ": " + (ticket.getSubject() != null ? ticket.getSubject() : "Untitled Ticket");

                updates.add(new SubscriptionUpdateResponse(
                    ticket.getId() + "::" + latestReply.getId(),
                    ticket.getId(),
                    ticketTitle,
                    latestReply.getContent(),
                    latestReply.getName(),
                    latestReply.getCreated(),
                    latestReply.isStaff(),
                    false,
                    unreadReplies.size() > 1 ? unreadReplies.size() - 1 : null
                ));
            }

            if (updates.size() >= safeLimit) {
                break;
            }
        }

        updates.sort((a, b) -> b.replyAt().compareTo(a.replyAt()));
        return updates.stream().limit(safeLimit).toList();
    }

    private int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_UPDATES_LIMIT));
    }

    private int ticketScanLimit(int limit) {
        return Math.min(MAX_TICKETS_TO_SCAN, Math.max(50, limit * 10));
    }

    public boolean markAsRead(Server server, String staffEmail, String updateId) {
        String ticketId = updateId.split("::")[0];
        ensureSubscription(server, ticketId, staffEmail);
        return staffRepository.markSubscriptionRead(server, staffEmail, ticketId, new Date());
    }

    public void ensureSubscription(Server server, String ticketId, String staffEmail) {
        Staff staff = staffRepository.findByEmailExact(server, staffEmail).orElse(null);
        if (staff == null) {
            log.debug("Staff member {} not found for ticket subscription", staffEmail);
            return;
        }

        if (staff.getSubscribedTickets() != null) {
            boolean alreadySubscribed = staff.getSubscribedTickets()
                .stream()
                .anyMatch(sub -> sub.getTicketId().equals(ticketId) && sub.isActive());
            if (alreadySubscribed) {
                return;
            }
        }

        Staff.TicketSubscription subscription = new Staff.TicketSubscription();
        subscription.setTicketId(ticketId);
        subscription.setSubscribedAt(new Date());
        subscription.setActive(true);
        staffRepository.addTicketSubscription(server, staffEmail, subscription);
        publishAssignedTicketSubscriptionChanged(server, staffEmail);
    }

    public void markTicketAsRead(Server server, String ticketId, String staffEmail) {
        ensureSubscription(server, ticketId, staffEmail);
        staffRepository.markSubscriptionRead(server, staffEmail, ticketId, new Date());
    }

    public List<SubscriptionUpdateResponse> getAssignedTicketUpdates(Server server, String staffEmail, int limit) {
        int safeLimit = clampLimit(limit);
        Staff staff = staffRepository.findByEmailExact(server, staffEmail).orElse(null);
        if (staff == null) {
            return Collections.emptyList();
        }

        String rawStaffIdentifier = staff.getUsername() != null ? staff.getUsername() : staffEmail.split("@")[0];
        String staffIdentifier = TicketAssigneeUtil.normalizeSingle(rawStaffIdentifier);
        if (staffIdentifier == null) {
            return Collections.emptyList();
        }

        List<Ticket> tickets = ticketRepository.findRecentAssignedTicketsWithReplies(
            server,
            staffIdentifier,
            ticketScanLimit(safeLimit)
        );

        List<SubscriptionUpdateResponse> updates = new ArrayList<>();
        Map<String, Date> lastSeenMap = new HashMap<>();
        if (staff.getSubscribedTickets() != null) {
            for (Staff.TicketSubscription sub : staff.getSubscribedTickets()) {
                if (sub.getLastReadAt() != null) {
                    lastSeenMap.put(sub.getTicketId(), sub.getLastReadAt());
                }
            }
        }

        for (Ticket ticket : tickets) {
            if (ticket.getReplies() == null || ticket.getReplies().isEmpty()) {
                continue;
            }

            Date lastSeen = lastSeenMap.get(ticket.getId());
            List<TicketReply> unreadReplies = ticket.getReplies()
                .stream()
                .filter(reply -> reply.getCreated() != null)
                .filter(reply -> {
                    if (!reply.isStaff()) {
                        return true;
                    }
                    String replyName = TicketAssigneeUtil.normalizeSingle(reply.getName());
                    return !staffIdentifier.equals(replyName);
                })
                .filter(reply -> lastSeen == null || reply.getCreated().after(lastSeen))
                .sorted((a, b) -> b.getCreated().compareTo(a.getCreated()))
                .toList();

            if (!unreadReplies.isEmpty()) {
                TicketReply latestReply = unreadReplies.get(0);
                String ticketTitle = ticket.getId() + ": " + (ticket.getSubject() != null ? ticket.getSubject() : "Untitled Ticket");

                updates.add(new SubscriptionUpdateResponse(
                    ticket.getId() + "::" + latestReply.getId(),
                    ticket.getId(),
                    ticketTitle,
                    latestReply.getContent(),
                    latestReply.getName(),
                    latestReply.getCreated(),
                    latestReply.isStaff(),
                    false,
                    unreadReplies.size() > 1 ? unreadReplies.size() - 1 : null
                ));
            }
        }

        updates.sort((a, b) -> b.replyAt().compareTo(a.replyAt()));
        return updates.stream().limit(safeLimit).toList();
    }

    private void publishAssignedTicketSubscriptionChanged(Server server, String staffEmail) {
        try {
            AssignedTicketSubscriptionChangedEvent.Builder payload = AssignedTicketSubscriptionChangedEvent.newBuilder();
            if (staffEmail != null) {
                payload.setStaffId(staffEmail);
            }
            realtimeEventDispatcher.publish(new RealtimeOutboundEvent(
                server.getId(),
                Topic.TOPIC_PANEL_ASSIGNED_TICKETS,
                RealtimeEnvelope.newBuilder()
                    .setEventId(server.getId() + "::assigned-ticket-sub::" + staffEmail + "::" + System.nanoTime())
                    .setAssignedTicketSubscriptionChanged(payload)
                    .build()
            ));
        } catch (RuntimeException ex) {
            log.warn("realtime publish failed for assigned ticket subscription change for staff {}", staffEmail, ex);
        }
    }
}
