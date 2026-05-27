package gg.modl.backend.settings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.Settings;
import gg.modl.backend.settings.data.WebhookSettings;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class WebhookSettingsService {
    private final SettingsRepositoryAccess settingsRepositoryAccess;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private static final String SETTINGS_TYPE_WEBHOOKS = "webhookSettings";
    private static final int DEFAULT_EMBED_COLOR = 3447003;

    public @NotNull WebhookSettings updateWebhookSettings(@NotNull Server server, @NotNull WebhookSettings newSettings) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.convertValue(newSettings, Map.class);

        settingsRepositoryAccess.upsertSettings(server, SETTINGS_TYPE_WEBHOOKS, data);

        return getWebhookSettings(server);
    }

    public @NotNull WebhookSettings getWebhookSettings(@NotNull Server server) {
        Settings settings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_WEBHOOKS).orElse(null);

        if (settings == null || settings.getData() == null) {
            return getDefaultWebhookSettings();
        }

        try {
            return objectMapper.convertValue(settings.getData(), WebhookSettings.class);
        } catch (Exception e) {
            log.error("Error converting webhook settings", e);
            return getDefaultWebhookSettings();
        }
    }

    private WebhookSettings getDefaultWebhookSettings() {
        return WebhookSettings.builder()
            .discordWebhookUrl("")
            .discordAdminRoleId("")
            .botName("modl Panel")
            .avatarUrl("")
            .enabled(false)
            .notifications(WebhookSettings.NotificationSettings.builder()
                .newTickets(true)
                .newPunishments(true)
                .auditLogs(false)
                .build())
            .embedTemplates(WebhookSettings.EmbedTemplates.builder()
                .newTickets(createDefaultTicketEmbed())
                .newPunishments(createDefaultPunishmentEmbed())
                .auditLogs(createDefaultAuditEmbed())
                .build())
            .build();
    }

    private WebhookSettings.EmbedTemplate createDefaultTicketEmbed() {
        return WebhookSettings.EmbedTemplate.builder()
            .title("New Ticket Created")
            .description("A new **{{type}}** ticket has been submitted.")
            .color("#3498db")
            .fields(List.of(
                new WebhookSettings.EmbedField("Ticket ID", "#{{id}}", true),
                new WebhookSettings.EmbedField("Priority", "{{priority}}", true),
                new WebhookSettings.EmbedField("Category", "{{category}}", true),
                new WebhookSettings.EmbedField("Subject", "{{title}}", false),
                new WebhookSettings.EmbedField("Submitted By", "{{submittedBy}}", true)
            ))
            .build();
    }

    private WebhookSettings.EmbedTemplate createDefaultPunishmentEmbed() {
        return WebhookSettings.EmbedTemplate.builder()
            .title("New Punishment Issued")
            .description("A new **{{type}}** punishment has been issued.")
            .color("#e74c3c")
            .fields(List.of(
                new WebhookSettings.EmbedField("Player", "{{playerName}}", true),
                new WebhookSettings.EmbedField("Punishment Type", "{{type}}", true),
                new WebhookSettings.EmbedField("Severity", "{{severity}}", true),
                new WebhookSettings.EmbedField("Duration", "{{duration}}", true),
                new WebhookSettings.EmbedField("Reason", "{{reason}}", false),
                new WebhookSettings.EmbedField("Issued By", "{{issuer}}", true)
            ))
            .build();
    }

    private WebhookSettings.EmbedTemplate createDefaultAuditEmbed() {
        return WebhookSettings.EmbedTemplate.builder()
            .title("Audit Log Entry")
            .description("A new audit log entry has been recorded.")
            .color("#f39c12")
            .fields(List.of(
                new WebhookSettings.EmbedField("User", "{{user}}", true),
                new WebhookSettings.EmbedField("Action", "{{action}}", true),
                new WebhookSettings.EmbedField("Target", "{{target}}", true),
                new WebhookSettings.EmbedField("Details", "{{details}}", false)
            ))
            .build();
    }

    public boolean testWebhook(Server server) {
        WebhookSettings settings = getWebhookSettings(server);
        String webhookUrl = settings.getDiscordWebhookUrl();

        if (webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }

        if (!isAllowedDiscordWebhookUrl(webhookUrl)) {
            log.warn("Rejected webhook test URL due to failed validation");
            return false;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = Map.of(
                "username", settings.getBotName() != null ? settings.getBotName() : "modl Panel",
                "avatar_url", settings.getAvatarUrl() != null ? settings.getAvatarUrl() : "",
                "embeds", List.of(Map.of(
                    "title", "Webhook Test",
                    "description", "This is a test message from modl Panel to verify your webhook is working correctly.",
                    "color", DEFAULT_EMBED_COLOR
                ))
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(webhookUrl, request, String.class);

            return true;
        } catch (Exception e) {
            log.error("Webhook test failed", e);
            return false;
        }
    }

    @Async
    public void sendTicketCreatedWebhook(Server server, Map<String, String> variables) {
        sendEventWebhook(server, variables,
            s -> s.getNotifications() != null && s.getNotifications().isNewTickets(),
            s -> s.getEmbedTemplates() != null ? s.getEmbedTemplates().getNewTickets() : null);
    }

    @Async
    public void sendPunishmentCreatedWebhook(Server server, Map<String, String> variables) {
        sendEventWebhook(server, variables,
            s -> s.getNotifications() != null && s.getNotifications().isNewPunishments(),
            s -> s.getEmbedTemplates() != null ? s.getEmbedTemplates().getNewPunishments() : null);
    }

    private void sendEventWebhook(Server server, Map<String, String> variables,
            Function<WebhookSettings, Boolean> isEnabled,
            Function<WebhookSettings, WebhookSettings.EmbedTemplate> getTemplate) {
        try {
            WebhookSettings settings = getWebhookSettings(server);

            if (!settings.isEnabled()) {
                return;
            }

            String webhookUrl = settings.getDiscordWebhookUrl();
            if (webhookUrl == null || webhookUrl.isBlank() || !isAllowedDiscordWebhookUrl(webhookUrl)) {
                return;
            }

            if (!Boolean.TRUE.equals(isEnabled.apply(settings))) {
                return;
            }

            WebhookSettings.EmbedTemplate template = getTemplate.apply(settings);
            if (template == null) {
                return;
            }

            Map<String, Object> embed = buildEmbedFromTemplate(template, variables);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("username", settings.getBotName() != null ? settings.getBotName() : "modl Panel");
            payload.put("avatar_url", settings.getAvatarUrl() != null ? settings.getAvatarUrl() : "");
            payload.put("embeds", List.of(embed));

            String adminRoleId = settings.getDiscordAdminRoleId();
            if (adminRoleId != null && !adminRoleId.isBlank()) {
                payload.put("content", "<@&" + adminRoleId + ">");
            }

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(webhookUrl, request, String.class);
        } catch (Exception e) {
            log.error("Failed to send webhook notification for server {}", server.getId(), e);
        }
    }

    private Map<String, Object> buildEmbedFromTemplate(WebhookSettings.EmbedTemplate template, Map<String, String> variables) {
        Map<String, Object> embed = new LinkedHashMap<>();

        embed.put("title", replaceVariables(template.getTitle(), variables));
        embed.put("description", replaceVariables(template.getDescription(), variables));
        embed.put("color", parseColor(template.getColor()));

        if (template.getFields() != null && !template.getFields().isEmpty()) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (WebhookSettings.EmbedField field : template.getFields()) {
                String value = replaceVariables(field.getValue(), variables);
                if (value.isBlank()) {
                    continue;
                }
                Map<String, Object> fieldMap = new LinkedHashMap<>();
                fieldMap.put("name", replaceVariables(field.getName(), variables));
                fieldMap.put("value", value);
                fieldMap.put("inline", field.isInline());
                fields.add(fieldMap);
            }
            embed.put("fields", fields);
        }

        return embed;
    }

    private String replaceVariables(String text, Map<String, String> variables) {
        if (text == null) {
            return "";
        }
        String result = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    private int parseColor(String hexColor) {
        if (hexColor == null || hexColor.isBlank()) {
            return DEFAULT_EMBED_COLOR;
        }
        try {
            String hex = hexColor.startsWith("#") ? hexColor.substring(1) : hexColor;
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return DEFAULT_EMBED_COLOR;
        }
    }

    private boolean isAllowedDiscordWebhookUrl(String webhookUrl) {
        try {
            URI uri = URI.create(webhookUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }

            String host = uri.getHost();
            if (host == null) {
                return false;
            }

            boolean allowedHost = "discord.com".equalsIgnoreCase(host) || "discordapp.com".equalsIgnoreCase(host);
            if (!allowedHost) {
                return false;
            }

            String path = uri.getPath();
            return path != null && path.startsWith("/api/webhooks/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
