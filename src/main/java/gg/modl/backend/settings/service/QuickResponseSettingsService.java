package gg.modl.backend.settings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.settings.dto.request.UpdateQuickResponsesRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuickResponseSettingsService {
    private final SettingsDocumentService settingsDocumentService;
    private final ObjectMapper objectMapper;
    private static final String SETTINGS_TYPE_QUICK_RESPONSES = "quickResponses";

    public @NotNull QuickResponseSettings getQuickResponseSettings(@NotNull Server server) {
        return getQuickResponseSettingsState(server).data();
    }

    public @NotNull VersionedSettings<QuickResponseSettings> getQuickResponseSettingsState(@NotNull Server server) {
        SettingsDocumentService.RawSettingsState state = settingsDocumentService.getRawState(server, SETTINGS_TYPE_QUICK_RESPONSES);
        QuickResponseSettings settings = mapToQuickResponseSettings(state.data());
        return new VersionedSettings<>(settings, state.version(), state.updatedAt());
    }

    public @Nullable QuickResponseSettings.Action findAction(@Nullable QuickResponseSettings settings, @Nullable String categoryId, @Nullable String actionId) {
        if (settings == null || settings.getCategories() == null || categoryId == null || actionId == null) {
            return null;
        }

        for (QuickResponseSettings.Category category : settings.getCategories()) {
            if (category == null || category.getId() == null || category.getActions() == null) {
                continue;
            }
            if (categoryId.equals(category.getId())) {
                for (QuickResponseSettings.Action action : category.getActions()) {
                    if (action != null && actionId.equals(action.getId())) {
                        return action;
                    }
                }
            }
        }

        return null;
    }

    public VersionedSettings<QuickResponseSettings> patchQuickResponseSettings(
        Server server,
        long expectedVersion,
        UpdateQuickResponsesRequest quickResponses
    ) {
        QuickResponseSettings currentSettings = getQuickResponseSettings(server);
        if (quickResponses != null && quickResponses.categories() != null) {
            currentSettings.setCategories(quickResponses.categories());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.convertValue(currentSettings, Map.class);
        SettingsDocumentService.RawSettingsState updated = settingsDocumentService.saveRawState(
            server,
            SETTINGS_TYPE_QUICK_RESPONSES,
            expectedVersion,
            new LinkedHashMap<>(data)
        );
        return new VersionedSettings<>(mapToQuickResponseSettings(updated.data()), updated.version(), updated.updatedAt());
    }

    public void updateQuickResponseSettings(Server server, UpdateQuickResponsesRequest quickResponses) {
        long expectedVersion = getQuickResponseSettingsState(server).version();
        patchQuickResponseSettings(server, expectedVersion, quickResponses);
    }

    private QuickResponseSettings mapToQuickResponseSettings(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return defaultQuickResponseSettings();
        }

        try {
            QuickResponseSettings mapped = objectMapper.convertValue(data, QuickResponseSettings.class);
            if (mapped.getCategories() == null) {
                mapped.setCategories(new ArrayList<>());
            }
            return mapped;
        } catch (Exception e) {
            log.error("Failed to parse quick response settings", e);
            return defaultQuickResponseSettings();
        }
    }

    private QuickResponseSettings defaultQuickResponseSettings() {
        QuickResponseSettings settings = new QuickResponseSettings();
        settings.setCategories(new ArrayList<>());
        return settings;
    }
}
