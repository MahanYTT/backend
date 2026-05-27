package gg.modl.backend.settings.controller;

import com.mongodb.MongoException;
import gg.modl.backend.ai.service.AITicketAnalysisService;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.GeneralSettings;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import gg.modl.backend.settings.data.TicketFormSettings;
import gg.modl.backend.settings.data.TicketLabelSettings;
import gg.modl.backend.settings.data.WebhookSettings;
import gg.modl.backend.settings.dto.request.ApplyAIPunishmentRequest;
import gg.modl.backend.settings.dto.request.PatchGeneralSettingsRequest;
import gg.modl.backend.settings.dto.request.PatchQuickResponsesRequest;
import gg.modl.backend.settings.dto.request.PatchReplayRetentionSettingsRequest;
import gg.modl.backend.settings.dto.request.PatchStatusThresholdSettingsRequest;
import gg.modl.backend.settings.dto.request.PatchTicketFormSettingsRequest;
import gg.modl.backend.settings.dto.request.PatchTicketLabelSettingsRequest;
import gg.modl.backend.settings.dto.request.UpdateAIModerationSettingsRequest;
import gg.modl.backend.settings.dto.request.UpdateQuickResponsesRequest;
import gg.modl.backend.settings.dto.request.UpdateWebhookSettingsRequest;
import gg.modl.backend.settings.service.AIModerationSettingsService;
import gg.modl.backend.settings.service.ApiKeySettingsService;
import gg.modl.backend.settings.service.GeneralSettingsService;
import gg.modl.backend.settings.service.IconUploadService;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.QuickResponseSettingsService;
import gg.modl.backend.settings.service.ReplayRetentionSettingsService;
import gg.modl.backend.settings.service.TicketFormSettingsService;
import gg.modl.backend.settings.service.TicketLabelSettingsService;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.settings.service.WebhookSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Date;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS)
@RequiredArgsConstructor
@Slf4j
public class PanelSettingsController {
    private final GeneralSettingsService generalSettingsService;
    private final TicketLabelSettingsService ticketLabelSettingsService;
    private final ApiKeySettingsService apiKeySettingsService;
    private final AIModerationSettingsService aiModerationSettingsService;
    private final WebhookSettingsService webhookSettingsService;
    private final TicketFormSettingsService ticketFormSettingsService;
    private final QuickResponseSettingsService quickResponseSettingsService;
    private final IconUploadService iconUploadService;
    private final AITicketAnalysisService aiTicketAnalysisService;
    private final OffenderThresholdSettingsService offenderThresholdSettingsService;
    private final PermissionService permissionService;
    private final ReplayRetentionSettingsService replayRetentionSettingsService;
    private final TenantMongoAccess tenantMongoAccess;

    @GetMapping("/general")
    public ResponseEntity<SettingsEnvelope<GeneralSettings>> getGeneralSettings(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(toEnvelope(generalSettingsService.getGeneralSettingsState(server)));
    }

    private <T> SettingsEnvelope<T> toEnvelope(VersionedSettings<T> settings) {
        return new SettingsEnvelope<>(
            settings.data(),
            new SettingsMeta(settings.version(), settings.updatedAt())
        );
    }

    @PatchMapping("/general")
    public ResponseEntity<SettingsEnvelope<GeneralSettings>> patchGeneralSettings(
        @RequestBody @Valid PatchGeneralSettingsRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        GeneralSettings patch = GeneralSettings.builder()
            .serverDisplayName(body.serverDisplayName())
            .discordWebhookUrl(body.discordWebhookUrl())
            .homepageIconUrl(body.homepageIconUrl())
            .panelIconUrl(body.panelIconUrl())
            .build();

        VersionedSettings<GeneralSettings> updated = generalSettingsService.patchGeneralSettings(
            server,
            body.expectedVersion(),
            patch
        );
        return ResponseEntity.ok(toEnvelope(updated));
    }

    @GetMapping("/ticket-labels")
    public ResponseEntity<SettingsEnvelope<TicketLabelSettings>> getTicketLabelSettings(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(toEnvelope(ticketLabelSettingsService.getTicketLabelSettingsState(server)));
    }

    @PatchMapping("/ticket-labels")
    public ResponseEntity<SettingsEnvelope<TicketLabelSettings>> patchTicketLabelSettings(
        @RequestBody @Valid PatchTicketLabelSettingsRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        VersionedSettings<TicketLabelSettings> updated = ticketLabelSettingsService.patchTicketLabelSettings(
            server,
            body.expectedVersion(),
            body.labels()
        );
        return ResponseEntity.ok(toEnvelope(updated));
    }

    @GetMapping("/status-thresholds")
    public ResponseEntity<SettingsEnvelope<OffenderThresholdSettings>> getStatusThresholds(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(toEnvelope(offenderThresholdSettingsService.getThresholdSettingsState(server)));
    }

    @PatchMapping("/status-thresholds")
    public ResponseEntity<SettingsEnvelope<OffenderThresholdSettings>> patchStatusThresholds(
        @RequestBody @Valid PatchStatusThresholdSettingsRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        VersionedSettings<OffenderThresholdSettings> updated = offenderThresholdSettingsService.patchThresholdSettings(
            server,
            body.expectedVersion(),
            body.settings()
        );
        return ResponseEntity.ok(toEnvelope(updated));
    }

    @GetMapping("/replay-retention")
    public ResponseEntity<SettingsEnvelope<ReplayRetentionSettings>> getReplayRetentionSettings(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(toEnvelope(replayRetentionSettingsService.getReplayRetentionSettingsState(server)));
    }

    @PatchMapping("/replay-retention")
    public ResponseEntity<SettingsEnvelope<ReplayRetentionSettings>> patchReplayRetentionSettings(
        @RequestBody @Valid PatchReplayRetentionSettingsRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        VersionedSettings<ReplayRetentionSettings> updated = replayRetentionSettingsService.patchReplayRetentionSettings(
            server,
            body.expectedVersion(),
            body.enabled(),
            body.days()
        );
        return ResponseEntity.ok(toEnvelope(updated));
    }

    @PostMapping("/api-keys/{type}/generate")
    public ResponseEntity<?> generateApiKey(
        @PathVariable String type,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        requireSuperAdmin(server, request);
        String apiKey = apiKeySettingsService.generateApiKey(server, type);
        return ResponseEntity.ok(Map.of(
            "message", "API key generated successfully",
            "apiKey", apiKey
        ));
    }

    @GetMapping("/api-keys/{type}/reveal")
    public ResponseEntity<?> revealApiKey(
        @PathVariable String type,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        requireSuperAdmin(server, request);
        String apiKey = apiKeySettingsService.revealApiKey(server, type);

        if (apiKey == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of("apiKey", apiKey));
    }

    @DeleteMapping("/api-keys/{type}")
    public ResponseEntity<?> deleteApiKey(
        @PathVariable String type,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        requireSuperAdmin(server, request);
        boolean deleted = apiKeySettingsService.deleteApiKey(server, type);

        if (!deleted) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of("message", "API key deleted successfully"));
    }

    @GetMapping("/api-keys/{type}/exists")
    public ResponseEntity<?> checkApiKeyExists(
        @PathVariable String type,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        requireSuperAdmin(server, request);
        boolean exists = apiKeySettingsService.hasApiKey(server, type);
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    @GetMapping("/ai-moderation")
    public ResponseEntity<AIModerationSettings> getAIModerationSettings(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        AIModerationSettings settings = aiModerationSettingsService.getAIModerationSettings(server);
        return ResponseEntity.ok(settings);
    }

    @PatchMapping("/ai-moderation")
    public ResponseEntity<AIModerationSettings> updateAIModerationSettings(
        @RequestBody @Valid UpdateAIModerationSettingsRequest requestBody,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        AIModerationSettings settings = requestBody.toSettings();
        AIModerationSettings updated = aiModerationSettingsService.updateAIModerationSettings(server, settings);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/webhooks")
    public ResponseEntity<WebhookSettings> getWebhookSettings(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        WebhookSettings settings = webhookSettingsService.getWebhookSettings(server);
        return ResponseEntity.ok(settings);
    }

    @PatchMapping("/webhooks")
    public ResponseEntity<WebhookSettings> updateWebhookSettings(
        @RequestBody @Valid UpdateWebhookSettingsRequest requestBody,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        WebhookSettings settings = requestBody.toSettings();
        WebhookSettings updated = webhookSettingsService.updateWebhookSettings(server, settings);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/webhooks/test")
    public ResponseEntity<?> testWebhook(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        boolean success = webhookSettingsService.testWebhook(server);

        if (success) {
            return ResponseEntity.ok(Map.of("message", "Webhook test sent successfully"));
        } else {
            throw new ValidationException("Failed to send webhook test");
        }
    }

    @PostMapping("/test-database")
    public ResponseEntity<?> testDatabase(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        try {
            tenantMongoAccess.forServer(server).getDb().runCommand(new Document("ping", 1));
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Database connection OK"
            ));
        } catch (MongoException e) {
            log.warn("Database connectivity test failed for server {}: {}", server.getServerName(), e.toString());
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Database connection failed"
            ));
        }
    }

    @GetMapping("/ticket-forms")
    public ResponseEntity<SettingsEnvelope<TicketFormSettings>> getTicketFormSettings(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(toEnvelope(ticketFormSettingsService.getTicketFormSettingsState(server)));
    }

    @PatchMapping("/ticket-forms")
    public ResponseEntity<SettingsEnvelope<TicketFormSettings>> patchTicketFormSettings(
        @RequestBody @Valid PatchTicketFormSettingsRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        VersionedSettings<TicketFormSettings> updated = ticketFormSettingsService.patchTicketFormSettings(
            server,
            body.expectedVersion(),
            body.settings()
        );
        return ResponseEntity.ok(toEnvelope(updated));
    }

    @GetMapping("/ticket-forms/{type}")
    public ResponseEntity<TicketFormSettings.TicketForm> getTicketForm(
        @PathVariable String type,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        TicketFormSettings.TicketForm form = ticketFormSettingsService.getFormByType(server, type);

        if (form == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(form);
    }

    @GetMapping("/quick-responses")
    public ResponseEntity<SettingsEnvelope<QuickResponseSettings>> getQuickResponses(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(toEnvelope(quickResponseSettingsService.getQuickResponseSettingsState(server)));
    }

    @PatchMapping("/quick-responses")
    public ResponseEntity<SettingsEnvelope<QuickResponseSettings>> patchQuickResponses(
        @RequestBody @Valid PatchQuickResponsesRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        VersionedSettings<QuickResponseSettings> updated = quickResponseSettingsService.patchQuickResponseSettings(
            server,
            body.expectedVersion(),
            new UpdateQuickResponsesRequest(body.categories())
        );
        return ResponseEntity.ok(toEnvelope(updated));
    }

    @PostMapping("/upload-icon")
    public ResponseEntity<?> uploadIcon(
        @RequestParam("icon") MultipartFile file,
        @RequestParam("iconType") String iconType,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return iconUploadService.uploadIcon(server, file, iconType);
    }

    @PostMapping("/ai-apply-punishment/{ticketId}")
    public ResponseEntity<?> applyAIPunishment(
        @PathVariable String ticketId,
        @RequestBody @Valid ApplyAIPunishmentRequest body,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String staffName = body.staffName() != null ? body.staffName() : "Staff";

        AITicketAnalysisService.AISuggestionResult result = aiTicketAnalysisService.applyAISuggestion(server, ticketId, staffName);
        if (!result.success()) {
            return ResponseEntity.badRequest().body(Map.of("error", result.error()));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/ai-dismiss-suggestion/{ticketId}")
    public ResponseEntity<?> dismissAISuggestion(
        @PathVariable String ticketId,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        AITicketAnalysisService.AISuggestionResult result = aiTicketAnalysisService.dismissAISuggestion(server, ticketId);
        if (!result.success()) {
            return ResponseEntity.badRequest().body(Map.of("error", result.error()));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    public record SettingsEnvelope<T>(T data, SettingsMeta _meta) {
    }

    public record SettingsMeta(long version, Date updatedAt) {
    }

    private void requireSuperAdmin(Server server, HttpServletRequest request) {
        String email = RequestUtil.getSessionEmail(request);
        if (!permissionService.isSuperAdmin(server, email)) {
            throw new ForbiddenException("Only super admins can manage API keys");
        }
    }

}
