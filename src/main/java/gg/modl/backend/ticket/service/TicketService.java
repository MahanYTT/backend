package gg.modl.backend.ticket.service;

import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.util.MongoKeyUtils;
import gg.modl.backend.infrastructure.util.UuidUtil;
import gg.modl.backend.realtime.dispatch.RealtimeEventDispatcher;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.settings.data.TicketFormSettings;
import gg.modl.backend.settings.service.QuickResponseSettingsService;
import gg.modl.backend.settings.service.TicketFormSettingsService;
import gg.modl.backend.settings.service.WebhookSettingsService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.request.BulkTicketUpdateRequest;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import gg.modl.backend.ticket.dto.request.QuickResponseRequest;
import gg.modl.backend.ticket.dto.request.SubmitTicketFormRequest;
import gg.modl.backend.ticket.dto.request.UpdateTicketRequest;
import gg.modl.backend.ticket.dto.response.QuickResponseResult;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.util.TicketAssigneeUtil;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.TicketChangedEvent;
import gg.modl.proto.modl.v1.Topic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketService {
    private final TicketMongoRepository ticketRepository;
    private final StaffMongoRepository staffRepository;
    private final QuickResponseSettingsService quickResponseSettingsService;
    private final TicketFormSettingsService ticketFormSettingsService;
    private final TicketNotificationService notificationService;
    private final TicketIdGenerator ticketIdGenerator;
    private final TicketContentService contentService;
    private final WebhookSettingsService webhookSettingsService;
    private final RealtimeEventDispatcher realtimeEventDispatcher;
    private static final String AVATAR_URL_FORMAT = "https://mc-heads.net/avatar/%s/32";

    public TicketResponse getTicketById(Server server, String ticketId) {
        Ticket ticket = ticketRepository.findById(server, ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
        return toTicketResponse(server, ticket);
    }

    private TicketResponse toTicketResponse(Server server, Ticket ticket) {
        List<TicketReply> processedReplies = processRepliesWithNames(server, ticket);
        String creatorName = ticket.getCreatorName() != null ? ticket.getCreatorName() : "Unknown";

        return new TicketResponse(
            ticket.getId(),
            ticket.getType() != null ? ticket.getType().getId() : TicketCategory.SUPPORT.getId(),
            ticket.getType() != null ? ticket.getType().getDisplayName() : TicketCategory.SUPPORT.getDisplayName(),
            ticket.getSubject() != null ? ticket.getSubject() : "No Subject",
            ticket.getStatus() != null ? ticket.getStatus().getId() : TicketStatus.OPEN.getId(),
            ticket.getAppealWorkflowStatus() != null ? ticket.getAppealWorkflowStatus().getId() : null,
            creatorName,
            ticket.getCreatorUuid(),
            creatorName,
            ticket.getReportedPlayer(),
            ticket.getReportedPlayerUuid(),
            ticket.getCreated(),
            ticket.isLocked(),
            processedReplies,
            ticket.getNotes(),
            ticket.getTags(),
            ticket.getFormData(),
            ticket.getData(),
            ticket.getChatMessages(),
            ticket.getAiAnalysis(),
            ticket.isEmailAuthEnabled(),
            ticket.isHidden(),
            ticket.getReplayUrl(),
            ticket.getAssignedTo()
        );
    }

    private List<TicketReply> processRepliesWithNames(Server server, Ticket ticket) {
        if (ticket.getReplies() == null || ticket.getReplies().isEmpty()) {
            return ticket.getReplies();
        }

        String creatorName = ticket.getCreatorName() != null ? ticket.getCreatorName() : "Player";

        Set<String> staffUsernames = new HashSet<>();
        for (TicketReply reply : ticket.getReplies()) {
            if (reply.isStaff() && (reply.getAvatar() == null || reply.getAvatar().isBlank()) && reply.getName() != null && !reply.getName().isBlank()) {
                staffUsernames.add(reply.getName());
            }
        }

        Map<String, String> staffAvatarMap = new HashMap<>();
        if (!staffUsernames.isEmpty()) {
            Map<String, Staff> staffByUsername = staffRepository.findByUsernames(server, staffUsernames)
                .stream()
                .collect(Collectors.toMap(Staff::getUsername, Function.identity(), (a, b) -> a));

            for (String username : staffUsernames) {
                Staff staff = staffByUsername.get(username);
                if (staff != null && staff.getAssignedMinecraftUuid() != null && !staff.getAssignedMinecraftUuid().isBlank()) {
                    staffAvatarMap.put(username, String.format(AVATAR_URL_FORMAT, staff.getAssignedMinecraftUuid()));
                }
            }
        }

        return ticket.getReplies()
            .stream().map(reply -> {
                if (reply.getName() == null || reply.getName().isBlank()) {
                    String fallbackName = reply.isStaff() ? "Staff" : creatorName;
                    reply.setName(fallbackName);
                }
                if (reply.getType() == null || reply.getType().isBlank()) {
                    reply.setType(reply.isStaff() ? "staff" : "user");
                }
                if (reply.isStaff() && (reply.getAvatar() == null || reply.getAvatar().isBlank()) && reply.getName() != null) {
                    String avatar = staffAvatarMap.get(reply.getName());
                    if (avatar != null) {
                        reply.setAvatar(avatar);
                    }
                }
                return reply;
            }).toList();
    }

    public Optional<Ticket> getTicketRaw(Server server, String ticketId) {
        return ticketRepository.findById(server, ticketId);
    }

    public TicketResponse createTicket(Server server, CreateTicketRequest request) {
        TicketCategory ticketCategory = TicketCategory.fromCanonicalId(request.type());
        String ticketId = ticketIdGenerator.generate(server, ticketCategory);

        boolean shouldOpenImmediately = ticketCategory.isReport()
                                        || (request.subject() != null && !request.subject().isBlank());
        TicketStatus ticketStatus = shouldOpenImmediately ? TicketStatus.OPEN : TicketStatus.UNFINISHED;
        String subject = (request.subject() != null && !request.subject().isBlank())
                         ? request.subject()
                         : ticketCategory.getDisplayName();

        List<String> tags = request.tags() != null ? new ArrayList<>(request.tags()) : new ArrayList<>();

        Map<String, Object> data = new HashMap<>();
        String creatorEmail = EmailAddressUtil.normalizeIfValid(request.creatorEmail());
        if (creatorEmail != null) {
            data.put("creatorEmail", creatorEmail);
        }
        if (request.creatorIdentifier() != null) {
            data.put("creatorIdentifier", request.creatorIdentifier());
        }

        TicketContentService.FormDataProcessingResult createFormDataProcessing = contentService.processFormDataForContent(request.formData());
        List<Object> initialAttachments = contentService.mergeAttachments(
            contentService.normalizeAttachments(request.attachments()),
            createFormDataProcessing.attachments()
        );

        List<TicketReply> replies = new ArrayList<>();
        String content = contentService.buildTicketContent(request, createFormDataProcessing.formData());
        if (!content.isBlank() || !initialAttachments.isEmpty()) {
            TicketReply initialReply = TicketReply.builder()
                .id(UUID.randomUUID().toString())
                .name(request.creatorName() != null ? request.creatorName() : "API User")
                .content(content)
                .type("user")
                .created(new Date())
                .staff(false)
                .attachments(initialAttachments)
                .creatorIdentifier(request.creatorIdentifier())
                .build();
            replies.add(initialReply);
        }

        String creatorDisplayName = request.creatorName() != null ? request.creatorName() : "API User";

        boolean emailAuth = Boolean.TRUE.equals(request.emailAuthEnabled());

        TicketFormSettings.TicketForm formSettings = ticketFormSettingsService.getFormByType(server, ticketCategory.getId());
        if (formSettings != null && formSettings.isRequireEmailAuth()) {
            emailAuth = true;
        }

        Ticket ticket = Ticket.builder()
            .id(ticketId)
            .type(ticketCategory)
            .subject(subject)
            .status(ticketStatus)
            .appealWorkflowStatus(ticketCategory.isAppeal() ? AppealWorkflowStatus.OPEN : null)
            .creatorName(creatorDisplayName)
            .creatorUuid(UuidUtil.normalizeUuid(request.creatorUuid()))
            .reportedPlayer(request.reportedPlayerName())
            .reportedPlayerUuid(UuidUtil.normalizeUuid(request.reportedPlayerUuid()))
            .tags(tags)
            .replies(replies)
            .notes(new ArrayList<>())
            .chatMessages(request.chatMessages() == null || request.chatMessages().isEmpty() ? null : contentService.sanitizeChatMessages(request.chatMessages()))
            .formData(MongoKeyUtils.sanitizeKeys(request.formData()))
            .data(data)
            .locked(ticketStatus.isTerminal())
            .priority(TicketPriority.resolveOrDefault(request.priority()))
            .emailAuthEnabled(emailAuth)
            .created(new Date())
            .updatedAt(new Date())
            .build();

        ticketRepository.saveEntity(server, ticket);
        publishTicketChanged(server, ticketId, "created");

        webhookSettingsService.sendTicketCreatedWebhook(server, Map.of(
            "id", ticketId,
            "type", ticketCategory.getDisplayName(),
            "title", subject,
            "priority", ticket.getPriority() != null ? ticket.getPriority().name() : "Normal",
            "category", ticketCategory.getDisplayName(),
            "submittedBy", creatorDisplayName
        ));

        return toTicketResponse(server, ticket);
    }

    public TicketResponse updateTicket(Server server, String ticketId, UpdateTicketRequest request, String staffEmail) {
        Ticket ticket = ticketRepository.findById(server, ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
        boolean wasClosed = ticket.getStatus() != null && ticket.getStatus().isTerminal();
        ticket.setUpdatedAt(new Date());

        if (request.status() != null) {
            ticket.applyLifecycleStatus(TicketStatus.fromCanonicalId(request.status()));
        }

        if (request.locked() != null) {
            TicketStatus nextStatus = request.locked()
                                      ? TicketStatus.CLOSED
                                      : (ticket.getStatus() == TicketStatus.UNFINISHED ? TicketStatus.UNFINISHED : TicketStatus.OPEN);
            ticket.applyLifecycleStatus(nextStatus);
        }

        if (request.tags() != null) {
            ticket.setTags(request.tags());
        }

        if (request.hidden() != null) {
            ticket.setHidden(request.hidden());
        }

        if (request.assignedTo() != null) {
            ticket.setAssignedTo(TicketAssigneeUtil.normalizeCollection(request.assignedTo()));
        }

        if (request.data() != null && !request.data().isEmpty()) {
            Map<String, Object> existingData = ticket.getData() != null ? new HashMap<>(ticket.getData()) : new HashMap<>();
            existingData.putAll(MongoKeyUtils.sanitizeKeys(request.data()));
            ticket.setData(existingData);
        }

        TicketReply newReply = null;
        if (request.newReply() != null) {
            newReply = TicketReply.builder()
                .id(UUID.randomUUID().toString())
                .name(request.newReply().name())
                .avatar(request.newReply().avatar())
                .content(request.newReply().content())
                .type(request.newReply().type() != null ? request.newReply().type() : "public")
                .created(new Date())
                .staff(request.newReply().staff())
                .action(request.newReply().action())
                .attachments(request.newReply().attachments() != null ? request.newReply().attachments() : new ArrayList<>())
                .build();

            ticket.addReply(newReply);
        }

        if (request.newNote() != null) {
            TicketNote newNote = TicketNote.builder()
                .text(request.newNote().text())
                .issuerName(request.newNote().issuerName())
                .issuerAvatar(request.newNote().issuerAvatar())
                .date(new Date())
                .build();

            ticket.addNote(newNote);
        }

        Ticket saved = ticketRepository.saveEntity(server, ticket);
        publishTicketChanged(server, saved.getId(), "updated");

        if (newReply != null && newReply.isStaff()) {
            notificationService.notifyTicketReply(server, saved, newReply);
        }

        if (!wasClosed && saved.getStatus() != null && saved.getStatus().isTerminal()) {
            notificationService.notifyTicketClosed(server, saved);
        }

        return toTicketResponse(server, saved);
    }

    public int bulkUpdateTickets(Server server, BulkTicketUpdateRequest request, String staffEmail) {
        List<Ticket> tickets = ticketRepository.findByIds(server, request.ticketIds());
        int updatedCount = 0;

        for (Ticket ticket : tickets) {
            Date now = new Date();
            ticket.setUpdatedAt(now);
            boolean hasChanges = false;
            boolean wasClosed = ticket.getStatus() != null && ticket.getStatus().isTerminal();

            if (request.locked() != null) {
                TicketStatus nextStatus = request.locked()
                                          ? TicketStatus.CLOSED
                                          : (ticket.getStatus() == TicketStatus.UNFINISHED ? TicketStatus.UNFINISHED : TicketStatus.OPEN);
                ticket.applyLifecycleStatus(nextStatus);
                hasChanges = true;
            }

            if (request.hidden() != null) {
                ticket.setHidden(request.hidden());
                hasChanges = true;
            }

            if (request.addLabels() != null && !request.addLabels().isEmpty()) {
                List<String> currentTags = ticket.getTags() != null ? new ArrayList<>(ticket.getTags()) : new ArrayList<>();
                for (String label : request.addLabels()) {
                    if (!currentTags.contains(label)) {
                        currentTags.add(label);
                    }
                }
                ticket.setTags(currentTags);
                hasChanges = true;
            }

            if (request.removeLabels() != null && !request.removeLabels().isEmpty()) {
                List<String> currentTags = ticket.getTags() != null ? new ArrayList<>(ticket.getTags()) : new ArrayList<>();
                currentTags.removeAll(request.removeLabels());
                ticket.setTags(currentTags);
                hasChanges = true;
            }

            if (request.assignTo() != null) {
                List<String> assignees = "none".equalsIgnoreCase(request.assignTo())
                                         ? List.of()
                                         : TicketAssigneeUtil.normalizeCsv(request.assignTo());
                ticket.setAssignedTo(assignees);
                hasChanges = true;
            }

            if (hasChanges) {
                Ticket saved = ticketRepository.saveEntity(server, ticket);
                updatedCount++;
                publishTicketChanged(server, saved.getId(), "bulk_updated");

                if (!wasClosed && saved.getStatus() != null && saved.getStatus().isTerminal()) {
                    notificationService.notifyTicketClosed(server, saved);
                }
            }
        }

        return updatedCount;
    }

    public QuickResponseResult processQuickResponse(Server server, String ticketId, QuickResponseRequest request, String staffUsername) {
        Ticket ticket = ticketRepository.findById(server, ticketId).orElse(null);
        if (ticket == null) {
            return new QuickResponseResult(false, "Ticket not found", null, null, false, false, null);
        }

        QuickResponseSettings settings = quickResponseSettingsService.getQuickResponseSettings(server);
        if (settings == null) {
            return new QuickResponseResult(false, "Quick response settings not found", ticketId, null, false, false, null);
        }

        QuickResponseSettings.Action action = quickResponseSettingsService.findAction(settings, request.categoryId(), request.actionId());
        if (action == null) {
            return new QuickResponseResult(false, "Action not found", ticketId, null, false, false, null);
        }

        TicketReply responseReply = TicketReply.builder()
            .id(UUID.randomUUID().toString())
            .name(staffUsername != null ? staffUsername : "System")
            .content(action.getMessage())
            .type("public")
            .created(new Date())
            .staff(true)
            .build();
        ticket.addReply(responseReply);
        ticket.setUpdatedAt(new Date());
        boolean ticketClosed = false;
        if (Boolean.TRUE.equals(action.getCloseTicket())) {
            ticket.applyLifecycleStatus(TicketStatus.CLOSED);
            ticketClosed = true;
        }

        Ticket saved = ticketRepository.saveEntity(server, ticket);
        publishTicketChanged(server, saved.getId(), "quick_response");

        notificationService.notifyTicketReply(server, saved, responseReply);

        if (ticketClosed) {
            notificationService.notifyTicketClosed(server, saved);
        }

        return new QuickResponseResult(
            true,
            "Quick response applied successfully",
            ticketId,
            action.getName(),
            ticketClosed,
            false,
            action.getAppealAction()
        );
    }

    public TicketResponse submitTicketForm(Server server, String ticketId, SubmitTicketFormRequest request) {
        Ticket ticket = ticketRepository.findById(server, ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
        ticket.applyLifecycleStatus(TicketStatus.OPEN);
        ticket.setUpdatedAt(new Date());

        Map<String, Object> requestFormData = request.formData() != null ? request.formData() : Collections.emptyMap();
        TicketContentService.FormDataProcessingResult formDataProcessing = contentService.processFormDataForContent(requestFormData);
        List<Object> initialAttachments = contentService.mergeAttachments(
            contentService.normalizeAttachments(request.attachments()),
            formDataProcessing.attachments()
        );

        if (request.subject() != null) {
            ticket.setSubject(request.subject());
        }

        Map<String, Object> existingData = ticket.getData() != null ? new HashMap<>(ticket.getData()) : new HashMap<>();
        boolean hasDataUpdates = false;

        if (request.formData() != null && !request.formData().isEmpty()) {
            Map<String, Object> sanitizedFormData = new HashMap<>(MongoKeyUtils.sanitizeKeys(request.formData()));

            existingData.putAll(sanitizedFormData);
            existingData.remove("creatorEmail");
            existingData.remove("creatorIdentifier");
            hasDataUpdates = true;

            Object emailAuthValue = request.formData().get("emailAuthEnabled");
            if (emailAuthValue != null && Boolean.parseBoolean(emailAuthValue.toString())) {
                ticket.setEmailAuthEnabled(true);
                existingData.put("emailAuthEnabled", true);
            }

            if (ticket.getType() != null) {
                TicketFormSettings.TicketForm formTypeSettings = ticketFormSettingsService.getFormByType(server, ticket.getType().getId());
                if (formTypeSettings != null && formTypeSettings.isRequireEmailAuth()) {
                    ticket.setEmailAuthEnabled(true);
                    existingData.put("emailAuthEnabled", true);
                }
            }

            ticket.setFormData(sanitizedFormData);
        }

        String creatorEmail = contentService.resolveCreatorEmail(request);
        if (creatorEmail != null) {
            existingData.put("creatorEmail", creatorEmail);
            hasDataUpdates = true;
        }

        if (request.creatorIdentifier() != null) {
            existingData.put("creatorIdentifier", request.creatorIdentifier());
            hasDataUpdates = true;
        }

        if (ticket.isEmailAuthEnabled()) {
            existingData.put("emailAuthEnabled", true);
            hasDataUpdates = true;
        }

        if (hasDataUpdates) {
            ticket.setData(existingData);
        }

        if (ticket.getReplies() == null || ticket.getReplies().isEmpty()) {
            String content = contentService.buildFormDataContent(formDataProcessing.formData(), request.fieldLabels());
            if (!content.isBlank() || !initialAttachments.isEmpty()) {
                TicketReply initialReply = TicketReply.builder()
                    .id(UUID.randomUUID().toString())
                    .name(ticket.getCreatorName() != null ? ticket.getCreatorName() : "Player")
                    .content(content)
                    .type("user")
                    .created(new Date())
                    .staff(false)
                    .attachments(initialAttachments)
                    .creatorIdentifier(request.creatorIdentifier())
                    .build();
                ticket.addReply(initialReply);
            }
        }

        Ticket saved = ticketRepository.saveEntity(server, ticket);
        publishTicketChanged(server, saved.getId(), "form_submitted");

        return toTicketResponse(server, saved);
    }

    public void closeTicketForPunishment(Server server, String ticketId, String issuerName) {
        Ticket ticket = ticketRepository.findById(server, ticketId).orElse(null);
        if (ticket == null || ticket.isLocked()) {
            return;
        }

        TicketReply systemReply = TicketReply.builder()
            .id(UUID.randomUUID().toString())
            .name(issuerName)
            .content("Report accepted - punishment has been issued.")
            .type("public")
            .created(new Date())
            .staff(true)
            .action("report_accepted")
            .attachments(new ArrayList<>())
            .build();

        ticket.addReply(systemReply);
        ticket.applyLifecycleStatus(TicketStatus.CLOSED);
        ticket.setUpdatedAt(new Date());
        ticketRepository.saveEntity(server, ticket);
        publishTicketChanged(server, ticketId, "closed_for_punishment");
    }

    public void reopenTicketForPunishment(Server server, String ticketId, String issuerName) {
        Ticket ticket = ticketRepository.findById(server, ticketId).orElse(null);
        if (ticket == null) {
            return;
        }

        ticket.applyLifecycleStatus(TicketStatus.OPEN);
        ticket.setUpdatedAt(new Date());
        ticketRepository.saveEntity(server, ticket);
        publishTicketChanged(server, ticketId, "reopened_for_punishment");
    }

    @Nullable
    public String getEmailHint(@NotNull Ticket ticket) {
        if (ticket.getData() == null) {
            return null;
        }
        Object email = ticket.getData().get("creatorEmail");
        if (email == null) {
            return null;
        }
        String emailStr = email.toString();
        int atIndex = emailStr.indexOf('@');
        if (atIndex <= 1) {
            return emailStr;
        }
        return emailStr.charAt(0) + "***" + emailStr.substring(atIndex);
    }

    private void publishTicketChanged(Server server, String ticketId, String changeKind) {
        try {
            TicketChangedEvent.Builder payload = TicketChangedEvent.newBuilder().setTicketId(ticketId);
            if (changeKind != null) {
                payload.setChangeKind(changeKind);
            }
            realtimeEventDispatcher.publish(new RealtimeOutboundEvent(
                server.getId(),
                Topic.TOPIC_PANEL_TICKETS,
                RealtimeEnvelope.newBuilder()
                    .setEventId(server.getId() + "::ticket::" + ticketId + "::" + System.nanoTime())
                    .setTicketChanged(payload)
                    .build()
            ));
        } catch (RuntimeException ex) {
            log.warn("realtime publish failed for ticket {} change {}", ticketId, changeKind, ex);
        }
    }

}
