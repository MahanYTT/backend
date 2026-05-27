package gg.modl.backend.settings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.GeneralSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeneralSettingsService {
    private final SettingsDocumentService settingsDocumentService;
    private final ObjectMapper objectMapper;
    private static final String SETTINGS_TYPE_GENERAL = "general";
    private static final int MAX_SERVER_NAME_LENGTH = 80;
    private static final int MAX_URL_LENGTH = 2048;

    public @NotNull GeneralSettings getGeneralSettings(@NotNull Server server) {
        return getGeneralSettingsState(server).data();
    }

    public @NotNull VersionedSettings<GeneralSettings> getGeneralSettingsState(@NotNull Server server) {
        SettingsDocumentService.RawSettingsState state = settingsDocumentService.getRawState(server, SETTINGS_TYPE_GENERAL);
        GeneralSettings settings = mapToGeneralSettings(state.data());
        return new VersionedSettings<>(settings, state.version(), state.updatedAt());
    }

    private GeneralSettings mapToGeneralSettings(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return defaultGeneralSettings();
        }

        try {
            GeneralSettings mapped = objectMapper.convertValue(data, GeneralSettings.class);
            String serverDisplayName = sanitize(mapped.getServerDisplayName(), MAX_SERVER_NAME_LENGTH);
            String discordWebhookUrl = sanitize(mapped.getDiscordWebhookUrl(), MAX_URL_LENGTH);
            String homepageIconUrl = sanitize(mapped.getHomepageIconUrl(), MAX_URL_LENGTH);
            String panelIconUrl = sanitize(mapped.getPanelIconUrl(), MAX_URL_LENGTH);

            return GeneralSettings.builder()
                .serverDisplayName(serverDisplayName != null ? serverDisplayName : "")
                .discordWebhookUrl(discordWebhookUrl != null ? discordWebhookUrl : "")
                .homepageIconUrl(homepageIconUrl != null ? homepageIconUrl : "")
                .panelIconUrl(panelIconUrl != null ? panelIconUrl : "")
                .build();
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to map general settings, using defaults: {}", exception.getMessage());
            return defaultGeneralSettings();
        }
    }

    private GeneralSettings defaultGeneralSettings() {
        return GeneralSettings.builder()
            .serverDisplayName("")
            .discordWebhookUrl("")
            .homepageIconUrl("")
            .panelIconUrl("")
            .build();
    }

    private String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    public @NotNull VersionedSettings<GeneralSettings> patchGeneralSettings(
        @NotNull Server server,
        long expectedVersion,
        @NotNull GeneralSettings patch
    ) {
        SettingsDocumentService.RawSettingsState current = settingsDocumentService.getRawState(server, SETTINGS_TYPE_GENERAL);
        Map<String, Object> data = new LinkedHashMap<>(current.data());

        putIfNotNull(data, "serverDisplayName", patch.getServerDisplayName(), MAX_SERVER_NAME_LENGTH);
        putIfNotNull(data, "discordWebhookUrl", patch.getDiscordWebhookUrl(), MAX_URL_LENGTH);
        putIfNotNull(data, "homepageIconUrl", patch.getHomepageIconUrl(), MAX_URL_LENGTH);
        putIfNotNull(data, "panelIconUrl", patch.getPanelIconUrl(), MAX_URL_LENGTH);

        SettingsDocumentService.RawSettingsState updated = settingsDocumentService.saveRawState(
            server,
            SETTINGS_TYPE_GENERAL,
            expectedVersion,
            data
        );
        return new VersionedSettings<>(mapToGeneralSettings(updated.data()), updated.version(), updated.updatedAt());
    }

    private void putIfNotNull(Map<String, Object> data, String key, String value, int maxLength) {
        if (value != null) {
            data.put(key, sanitize(value, maxLength));
        }
    }
}
