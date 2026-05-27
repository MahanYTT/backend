package gg.modl.backend.ticket.service;

import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.infrastructure.util.PaginationHelper;
import gg.modl.backend.infrastructure.util.UuidUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.response.PaginatedTicketsResponse;
import gg.modl.backend.ticket.dto.response.TicketListItemResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketSearchService {
    private final TicketMongoRepository ticketRepository;

    public PaginatedTicketsResponse searchTickets(Server server, int page, int limit, String search, String status, String type) {
        List<String> types = type != null && !type.isBlank() ? List.of(type) : null;
        return searchTickets(server, page, limit, search, status, types, null, null, null, "newest");
    }

    public PaginatedTicketsResponse searchTickets(Server server, int page, int limit, String search, String status, List<String> types,
                                                  String author, List<String> labels, List<String> assignees, String sort) {
        TicketMongoRepository.TicketSearchFilter filter = new TicketMongoRepository.TicketSearchFilter(
            search,
            status,
            types,
            author,
            labels,
            assignees
        );
        TicketMongoRepository.TicketSearchPage searchPage = ticketRepository.searchTickets(
            server,
            filter,
            TicketMongoRepository.TicketSortOption.from(sort),
            page,
            limit
        );

        List<TicketListItemResponse> ticketItems = searchPage.tickets()
            .stream()
            .map(this::toListItemResponse)
            .toList();

        int totalPages = PaginationHelper.calculateTotalPages(searchPage.total(), limit);

        return new PaginatedTicketsResponse(
            ticketItems,
            new PaginatedTicketsResponse.PaginationInfo(
                page,
                totalPages,
                limit,
                searchPage.total(),
                page < totalPages,
                page > 1
            ),
            new PaginatedTicketsResponse.FiltersInfo(search, status, types)
        );
    }

    private TicketListItemResponse toListItemResponse(Ticket ticket) {
        TicketReply lastReply = null;
        int replyCount = 0;
        String creatorName = ticket.getCreatorName() != null ? ticket.getCreatorName() : "Unknown";

        if (ticket.getReplies() != null && !ticket.getReplies().isEmpty()) {
            replyCount = ticket.getReplies().size();
            lastReply = ticket.getReplies().get(replyCount - 1);
        }

        return new TicketListItemResponse(
            ticket.getId(),
            ticket.getSubject() != null ? ticket.getSubject() : "No Subject",
            ticket.getStatus() != null ? ticket.getStatus().getId() : TicketStatus.OPEN.getId(),
            creatorName,
            creatorName,
            ticket.getCreated(),
            ticket.getType() != null ? ticket.getType().getDisplayName() : TicketCategory.SUPPORT.getDisplayName(),
            ticket.isLocked(),
            ticket.getType() != null ? ticket.getType().getId() : TicketCategory.SUPPORT.getId(),
            lastReply,
            replyCount,
            ticket.getTags() != null ? ticket.getTags() : new ArrayList<>(),
            ticket.getAssignedTo() != null ? ticket.getAssignedTo() : List.of(),
            ticket.isHidden()
        );
    }

    public Map<String, Long> getTicketCounts(Server server, String search, List<String> types, String author, List<String> labels, List<String> assignees) {
        TicketMongoRepository.TicketCounts ticketCounts = ticketRepository.countTickets(
            server,
            new TicketMongoRepository.TicketSearchFilter(search, null, types, author, labels, assignees)
        );

        Map<String, Long> result = new HashMap<>();
        result.put("open", ticketCounts.open());
        result.put("closed", ticketCounts.closed());
        return result;
    }

    public List<Ticket> getTicketsByPlayer(Server server, String playerUuid) {
        return ticketRepository.findByPlayer(server, UuidUtil.normalizeUuid(playerUuid));
    }

    public List<Ticket> getTicketsByTag(Server server, String tag) {
        return ticketRepository.findByTag(server, tag);
    }

}
