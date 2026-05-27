package gg.modl.backend.settings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.Settings;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AIModerationSettingsService {
    private final SettingsRepositoryAccess settingsRepositoryAccess;
    private final ObjectMapper objectMapper;
    private static final String SETTINGS_TYPE_AI_MODERATION = "aiModerationSettings";

    public @NotNull AIModerationSettings updateAIModerationSettings(@NotNull Server server, @NotNull AIModerationSettings newSettings) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.convertValue(newSettings, Map.class);
        settingsRepositoryAccess.upsertSettings(server, SETTINGS_TYPE_AI_MODERATION, data);
        return getAIModerationSettings(server);
    }

    public @NotNull AIModerationSettings getAIModerationSettings(@NotNull Server server) {
        Settings settings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_AI_MODERATION).orElse(null);

        if (settings == null || settings.getData() == null) {
            AIModerationSettings defaults = createDefaultSettings();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.convertValue(defaults, Map.class);
                settingsRepositoryAccess.saveEntity(server, new Settings(null, SETTINGS_TYPE_AI_MODERATION, data));
            } catch (Exception e) {
                log.warn("Failed to create default AI moderation settings for server {}", server.getDatabaseName(), e);
            }
            return defaults;
        }

        try {
            return objectMapper.convertValue(settings.getData(), AIModerationSettings.class);
        } catch (Exception e) {
            log.error("Error converting AI moderation settings", e);
            return AIModerationSettings.builder()
                .enableAIReview(false)
                .enableAutomatedActions(false)
                .aiPunishmentConfigs(new HashMap<>())
                .build();
        }
    }

    private AIModerationSettings createDefaultSettings() {
        Map<String, AIModerationSettings.AIPunishmentConfig> defaultConfigs = new HashMap<>();
        defaultConfigs.put("6", AIModerationSettings.AIPunishmentConfig.builder()
            .id("6")
            .name("Chat Abuse")
            .aiDescription(
                "Chat abuse is the act of spamming, excessive profanity, abusive language, inappropriate topics or jokes, and misleading information")
            .enabled(true)
            .build());
        defaultConfigs.put("7", AIModerationSettings.AIPunishmentConfig.builder()
            .id("7")
            .name("Anti Social")
            .aiDescription(
                "Anti social is the act of harassing, threatening, black-mailing, or otherwise abusing another player or group of players. This includes bigotry and other forms of discrimination against protected classes.")
            .enabled(true)
            .build());

        return AIModerationSettings.builder()
            .enableAIReview(false)
            .enableAutomatedActions(false)
            .aiPunishmentConfigs(defaultConfigs)
            .build();
    }
}
