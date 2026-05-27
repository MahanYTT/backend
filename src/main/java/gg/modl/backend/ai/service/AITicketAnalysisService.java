package gg.modl.backend.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.admin.data.SystemPrompt;
import gg.modl.backend.ai.LLMService;
import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.ai.data.DefaultPrompts;
import gg.modl.backend.billing.service.UsageTrackingService;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.SystemPromptMongoRepository;
import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.AIModerationSettings.AIPunishmentConfig;
import gg.modl.backend.settings.service.AIModerationSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AITicketAnalysisService {
    private final LLMService llmService;
    private final AIModerationSettingsService aiModerationSettingsService;
    private final TicketMongoRepository ticketRepository;
    private final ServerMongoRepository serverRepository;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final PunishmentTypeService punishmentTypeService;
    private final UsageTrackingService usageTrackingService;
    private final ObjectMapper objectMapper;
    private final SystemPromptMongoRepository systemPromptRepository;
    public static final String AI_MODERATOR = "AI Moderator";

    @Async
    public void analyzeTicketAsync(@NotNull Server server, @NotNull String ticketId) {
        if (!shouldAnalyze(server)) {
            log.debug("Skipping AI analysis for ticket {}: preconditions not met", ticketId);
            return;
        }

        final Ticket ticket = ticketRepository.findById(server, ticketId).orElse(null);

        if (ticket == null) {
            log.debug("Ticket {} not found for AI analysis", ticketId);
            return;
        }

        if (!isChatReport(ticket)) {
            log.debug("Skipping AI analysis for ticket {}: not a chat report", ticketId);
            return;
        }

        if (ticket.getChatMessages() == null || ticket.getChatMessages().isEmpty()) {
            log.debug("Skipping AI analysis for ticket {}: no chat messages", ticketId);
            return;
        }

        final AIModerationSettings settings = aiModerationSettingsService.getAIModerationSettings(server);
        final String promptTemplate = getSystemPrompt();
        final String chatLog = ticket.getChatMessages()
            .stream().map(Ticket.ChatMessage::getContent).collect(Collectors.joining("\n"));
        final String fullPrompt = buildPrompt(promptTemplate, chatLog, ticket.getReportedPlayer(), settings);

        final String rawResponse;
        try {
            rawResponse = llmService.generate(fullPrompt);
            usageTrackingService.incrementAiRequests(server.getId(), 1);
        } catch (Exception e) {
            log.error("LLM generation failed for ticket {}", ticketId, e);
            return;
        }

        final AIAnalysisResult result = parseResponse(rawResponse);
        if (result == null) {
            // parseResponse already logged the parse failure; analysis is best-effort.
            return;
        }

        ticket.setAiAnalysis(result);
        ticket.setUpdatedAt(new Date());
        ticketRepository.saveEntity(server, ticket);
    }

    private boolean shouldAnalyze(@NotNull Server server) {
        if (!llmService.isAvailable()) {
            log.debug("LLM service not available");
            return false;
        }

        if (server.getPlan() != ServerPlan.PREMIUM) {
            return false;
        }

        final AIModerationSettings settings = aiModerationSettingsService.getAIModerationSettings(server);
        if (!settings.isEnableAIReview() || settings.getAiPunishmentConfigs().isEmpty()) {
            return false;
        }

        // Avoid loading the full server document by using a lightweight usage snapshot.
        final ServerMongoRepository.AIUsageSnapshot usageSnapshot = serverRepository.findAIUsageSnapshotById(server.getId()).orElse(null);
        if (usageSnapshot != null) {
            final long currentUsage = usageSnapshot.aiRequestsCurrentPeriod();
            final long limit = usageTrackingService.getAiBaseLimitRequests() + Math.max(0L, usageSnapshot.maxAiOverageRequests());
            if (currentUsage >= limit) {
                log.debug("Server {} has reached AI request limit ({}/{})", server.getServerName(), currentUsage, limit);
                return false;
            }
        }

        return true;
    }

    private static boolean isChatReport(Ticket ticket) {
        return ticket.getType() == TicketCategory.CHAT;
    }

    private void executeAutomatedAction(Server server, Ticket ticket, AIAnalysisResult result, AIModerationSettings settings) {
        final AIAnalysisResult.SuggestedAction suggestion = result.getSuggestedAction();
        if (suggestion == null) {
            return;
        }

        if (ticket.getReportedPlayerUuid() == null || ticket.getReportedPlayerUuid().isBlank()) {
            log.warn("Cannot execute automated action: no reported player UUID for ticket {}", ticket.getId());
            return;
        }

        if (settings.getAiPunishmentConfigs() == null) {
            log.warn("No punishment configs available for automated action on ticket {}", ticket.getId());
            return;
        }

        final String typeKey = String.valueOf(suggestion.getPunishmentTypeId());
        final AIPunishmentConfig punishmentConfig = settings.getAiPunishmentConfigs().get(typeKey);

        if (punishmentConfig == null || !punishmentConfig.isEnabled()) {
            log.warn("Punishment config not found or disabled for type ordinal {}", typeKey);
            return;
        }

        try {
            applyPunishmentAndCloseTicket(server, ticket, result, AI_MODERATOR);
            result.setWasAppliedAutomatically(true);
        } catch (Exception e) {
            log.error("Failed to apply automated punishment for ticket {}", ticket.getId(), e);
        }
    }

    private void applyPunishmentAndCloseTicket(Server server, Ticket ticket, AIAnalysisResult aiAnalysis, String staffName) {
        final UUID playerUuid = UUID.fromString(ticket.getReportedPlayerUuid());
        final AIAnalysisResult.SuggestedAction suggestion = aiAnalysis.getSuggestedAction();
        final String reason = aiAnalysis.getAnalysis();

        final CreatePunishmentRequest request = new CreatePunishmentRequest(
            staffName,
            null,
            suggestion.getPunishmentTypeId(),
            null, null,
            List.of(ticket.getId()),
            suggestion.getSeverity(),
            "active",
            Map.of("aiGenerated", true),
            reason, null
        );

        punishmentLifecycleService.createPunishment(server, playerUuid, request);

        final Date now = new Date();
        final String typeName = punishmentTypeService.getPunishmentTypeName(server, suggestion.getPunishmentTypeId());

        final TicketReply systemReply = TicketReply.builder()
            .id(UUID.randomUUID().toString())
            .name(staffName)
            .content("This report has been reviewed and appropriate action has been taken. Thank you for your report.")
            .type("system")
            .created(now)
            .staff(true)
            .action("Close")
            .attachments(new ArrayList<>())
            .build();

        final TicketNote staffNote = TicketNote.builder()
            .text("AI Analysis by " + staffName + ": " + typeName + " (" + suggestion.getSeverity() + "). Reason: " + reason)
            .issuerName(staffName)
            .date(now)
            .build();

        aiAnalysis.setWasAppliedAutomatically(true);
        ticket.addReply(systemReply);
        ticket.addNote(staffNote);
        ticket.setStatus(TicketStatus.CLOSED);
        ticket.setLocked(true);
        ticket.setUpdatedAt(now);
    }

    private String getSystemPrompt() {
        final SystemPrompt prompt = systemPromptRepository.findActive().orElse(null);

        if (prompt != null && prompt.getPrompt() != null && !prompt.getPrompt().isBlank()) {
            return prompt.getPrompt();
        }

        return getDefaultPrompt();
    }

    @NotNull
    public static String getDefaultPrompt() {
        return DefaultPrompts.MINECRAFT;
    }

    @NotNull
    private String buildPrompt(@NotNull String promptTemplate, @NotNull String chatLog, @NotNull String reportedPlayer, @NotNull AIModerationSettings settings) {
        return promptTemplate
            .replace("{{REPORTED_PLAYER}}", reportedPlayer)
            .replace("{{PUNISHMENT_TYPES}}", formatPunishmentTypes(settings))
            .replace("{{CHAT_LOG}}", chatLog);
    }

    @NotNull
    private static String formatPunishmentTypes(@NotNull AIModerationSettings settings) {
        if (settings.getAiPunishmentConfigs() == null || settings.getAiPunishmentConfigs().isEmpty()) {
            return "No punishment types configured";
        }

        return settings.getAiPunishmentConfigs().values()
            .stream()
            .filter(AIPunishmentConfig::isEnabled)
            .map(config -> {
                String description = config.getAiDescription();
                return "%s: (%s) %s".formatted(
                    config.getId(),
                    config.getName(),
                    description != null && !description.isBlank() ? description : config.getName()
                );
            })
            .collect(Collectors.joining("\n"));
    }

    @Nullable
    private AIAnalysisResult parseResponse(@NotNull String rawResponse) {
        try {
            final String jsonContent = extractJson(rawResponse);
            final JsonNode json = objectMapper.readTree(jsonContent);
            final String analysis = json.has("analysis") ? json.get("analysis").asText() : null;

            if (analysis == null) {
                return null;
            }

            AIAnalysisResult.SuggestedAction suggestedAction = null;
            if (json.has("suggestedAction") && !json.get("suggestedAction").isNull()) {
                final JsonNode actionNode = json.get("suggestedAction");
                final Integer punishmentTypeId = parseIntField(actionNode, "punishmentTypeId");
                final String severity = actionNode.get("severity").asText();

                if (punishmentTypeId != null && severity != null) {
                    suggestedAction = new AIAnalysisResult.SuggestedAction(punishmentTypeId, severity);
                }
            }

            return new AIAnalysisResult(analysis, suggestedAction, new Date(), rawResponse);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse AI response: {}", rawResponse, e);
            return null;
        }
    }

    @Nullable
    private Integer parseIntField(@Nullable JsonNode node, @Nullable String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }

        final JsonNode value = node.get(field);
        if (value.isNumber()) {
            return value.asInt();
        }

        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException e) {
                log.warn("Non-numeric value for {}: {}", field, value.asText());
                return null;
            }
        }

        return null;
    }

    @NotNull
    private String extractJson(@NotNull String response) {
        final String trimmed = response.trim();
        final int start = trimmed.indexOf('{');
        final int end = trimmed.lastIndexOf('}');

        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return trimmed;
    }

    @NotNull
    public AISuggestionResult applyAISuggestion(@NotNull Server server, @NotNull String ticketId, @NotNull String staffName) {
        final Ticket ticket = ticketRepository.findById(server, ticketId).orElse(null);

        if (ticket == null) {
            return new AISuggestionResult(false, "Ticket not found");
        }

        final AIAnalysisResult aiAnalysis = ticket.getAiAnalysis();
        if (aiAnalysis == null || aiAnalysis.getSuggestedAction() == null) {
            return new AISuggestionResult(false, "No AI suggestion to apply");
        }

        if (aiAnalysis.isWasAppliedAutomatically() || aiAnalysis.isDismissed()) {
            return new AISuggestionResult(false, "AI suggestion already handled");
        }

        if (ticket.getReportedPlayerUuid() == null || ticket.getReportedPlayerUuid().isBlank()) {
            return new AISuggestionResult(false, "No reported player UUID");
        }

        applyPunishmentAndCloseTicket(server, ticket, aiAnalysis, staffName);
        ticketRepository.saveEntity(server, ticket);

        return new AISuggestionResult(true, null);
    }

    @NotNull
    public AISuggestionResult dismissAISuggestion(@NotNull Server server, @NotNull String ticketId) {
        final Ticket ticket = ticketRepository.findById(server, ticketId).orElse(null);

        if (ticket == null) {
            return new AISuggestionResult(false, "Ticket not found");
        }

        if (ticket.getAiAnalysis() == null) {
            return new AISuggestionResult(false, "No AI analysis to dismiss");
        }

        ticket.getAiAnalysis().setDismissed(true);
        ticket.setUpdatedAt(new Date());
        ticketRepository.saveEntity(server, ticket);

        return new AISuggestionResult(true, null);
    }

    public record AISuggestionResult(boolean success, String error) {}
}
