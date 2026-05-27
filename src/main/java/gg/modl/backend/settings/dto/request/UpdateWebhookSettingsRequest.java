package gg.modl.backend.settings.dto.request;

import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.settings.data.WebhookSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateWebhookSettingsRequest(
    @Size(max = RequestValidationLimits.WEBHOOK_URL_MAX_LENGTH)
    String discordWebhookUrl,
    @Size(max = RequestValidationLimits.DISCORD_ADMIN_ROLE_ID_MAX_LENGTH)
    String discordAdminRoleId,
    @Size(max = RequestValidationLimits.DISCORD_BOT_NAME_MAX_LENGTH)
    String botName,
    @Size(max = RequestValidationLimits.WEBHOOK_URL_MAX_LENGTH)
    String avatarUrl,
    Boolean enabled,
    @Valid NotificationSettingsRequest notifications,
    @Valid EmbedTemplatesRequest embedTemplates
) {
    public WebhookSettings toSettings() {
        return WebhookSettings.builder()
            .discordWebhookUrl(discordWebhookUrl)
            .discordAdminRoleId(discordAdminRoleId)
            .botName(botName)
            .avatarUrl(avatarUrl)
            .enabled(Boolean.TRUE.equals(enabled))
            .notifications(notifications != null ? notifications.toSettings() : null)
            .embedTemplates(embedTemplates != null ? embedTemplates.toSettings() : null)
            .build();
    }

    public record NotificationSettingsRequest(
        Boolean newTickets,
        Boolean newPunishments,
        Boolean auditLogs
    ) {
        public WebhookSettings.NotificationSettings toSettings() {
            return WebhookSettings.NotificationSettings.builder()
                .newTickets(Boolean.TRUE.equals(newTickets))
                .newPunishments(Boolean.TRUE.equals(newPunishments))
                .auditLogs(Boolean.TRUE.equals(auditLogs))
                .build();
        }
    }

    public record EmbedTemplatesRequest(
        @Valid EmbedTemplateRequest newTickets,
        @Valid EmbedTemplateRequest newPunishments,
        @Valid EmbedTemplateRequest auditLogs
    ) {
        public WebhookSettings.EmbedTemplates toSettings() {
            return WebhookSettings.EmbedTemplates.builder()
                .newTickets(newTickets != null ? newTickets.toSettings() : null)
                .newPunishments(newPunishments != null ? newPunishments.toSettings() : null)
                .auditLogs(auditLogs != null ? auditLogs.toSettings() : null)
                .build();
        }
    }

    public record EmbedTemplateRequest(
        @Size(max = RequestValidationLimits.EMBED_TITLE_MAX_LENGTH)
        String title,
        @Size(max = RequestValidationLimits.EMBED_DESCRIPTION_MAX_LENGTH)
        String description,
        @Size(max = RequestValidationLimits.EMBED_COLOR_MAX_LENGTH)
        String color,
        @Size(max = RequestValidationLimits.EMBED_FIELDS_MAX_ENTRIES)
        List<@Valid EmbedFieldRequest> fields
    ) {
        public WebhookSettings.EmbedTemplate toSettings() {
            return WebhookSettings.EmbedTemplate.builder()
                .title(title)
                .description(description)
                .color(color)
                .fields(fields != null ? fields.stream().map(EmbedFieldRequest::toSettings).toList() : null)
                .build();
        }
    }

    public record EmbedFieldRequest(
        @NotBlank
        @Size(max = RequestValidationLimits.EMBED_FIELD_NAME_MAX_LENGTH)
        String name,
        @NotBlank
        @Size(max = RequestValidationLimits.EMBED_FIELD_VALUE_MAX_LENGTH)
        String value,
        Boolean inline
    ) {
        public WebhookSettings.EmbedField toSettings() {
            return WebhookSettings.EmbedField.builder()
                .name(name)
                .value(value)
                .inline(Boolean.TRUE.equals(inline))
                .build();
        }
    }
}
