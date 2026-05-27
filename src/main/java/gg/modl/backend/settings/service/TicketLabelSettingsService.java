package gg.modl.backend.settings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.Label;
import gg.modl.backend.settings.data.TicketLabelSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketLabelSettingsService {
    private final SettingsDocumentService settingsDocumentService;
    private final ObjectMapper objectMapper;
    private static final String SETTINGS_TYPE_TICKET_LABELS = "ticketLabels";
    private static final int MAX_LABELS = 100;
    private static final int MAX_LABEL_ID_LENGTH = 64;
    private static final int MAX_LABEL_NAME_LENGTH = 64;
    private static final int MAX_LABEL_DESCRIPTION_LENGTH = 512;
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$");

    public @NotNull TicketLabelSettings getTicketLabelSettings(@NotNull Server server) {
        return getTicketLabelSettingsState(server).data();
    }

    public @NotNull VersionedSettings<TicketLabelSettings> getTicketLabelSettingsState(@NotNull Server server) {
        SettingsDocumentService.RawSettingsState state = settingsDocumentService.getRawState(server, SETTINGS_TYPE_TICKET_LABELS);
        TicketLabelSettings settings = mapToTicketLabelSettings(state.data());
        return new VersionedSettings<>(settings, state.version(), state.updatedAt());
    }

    public VersionedSettings<TicketLabelSettings> patchTicketLabelSettings(
        Server server,
        long expectedVersion,
        List<Label> labels
    ) {
        SettingsDocumentService.RawSettingsState current = settingsDocumentService.getRawState(server, SETTINGS_TYPE_TICKET_LABELS);
        Map<String, Object> data = new LinkedHashMap<>(current.data());

        if (labels != null) {
            List<Map<String, Object>> normalizedLabels = normalizeLabels(labels).stream()
                .map(this::labelToMap)
                .collect(Collectors.toCollection(ArrayList::new));
            data.put("labels", normalizedLabels);
        }

        SettingsDocumentService.RawSettingsState updated = settingsDocumentService.saveRawState(
            server,
            SETTINGS_TYPE_TICKET_LABELS,
            expectedVersion,
            data
        );
        return new VersionedSettings<>(mapToTicketLabelSettings(updated.data()), updated.version(), updated.updatedAt());
    }

    @SuppressWarnings("unchecked")
    private TicketLabelSettings mapToTicketLabelSettings(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return TicketLabelSettings.builder().labels(new ArrayList<>()).build();
        }

        Object rawLabels = data.get("labels");
        if (!(rawLabels instanceof List<?> labelsList)) {
            return TicketLabelSettings.builder().labels(new ArrayList<>()).build();
        }

        List<Label> labels = new ArrayList<>();
        for (Object rawLabel : labelsList) {
            if (rawLabel instanceof Map<?, ?> labelMap) {
                labels.add(mapToLabel((Map<String, Object>) labelMap));
                continue;
            }
            try {
                labels.add(mapToLabel(objectMapper.convertValue(rawLabel, Map.class)));
            } catch (IllegalArgumentException exception) {
                log.warn("Skipping malformed ticket label entry: {}", exception.getMessage());
            }
        }

        return TicketLabelSettings.builder()
            .labels(normalizeLabels(labels))
            .build();
    }

    private List<Label> normalizeLabels(List<Label> labels) {
        if (labels == null) {
            return new ArrayList<>();
        }

        List<Label> normalized = labels.stream()
            .filter(Objects::nonNull)
            .map(this::normalizeLabel)
            .filter(label -> label.getName() != null && !label.getName().isBlank())
            .collect(Collectors.toCollection(ArrayList::new));

        LinkedHashSet<String> seenNames = new LinkedHashSet<>();
        List<Label> deduped = new ArrayList<>();
        for (Label label : normalized) {
            String key = label.getName().toLowerCase(Locale.ROOT);
            if (seenNames.contains(key)) {
                continue;
            }
            seenNames.add(key);
            deduped.add(label);
        }

        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        List<Label> normalizedIds = new ArrayList<>();
        for (Label label : deduped) {
            String resolvedId = label.getId();
            while (resolvedId == null || resolvedId.isBlank() || seenIds.contains(resolvedId)) {
                resolvedId = UUID.randomUUID().toString();
            }
            seenIds.add(resolvedId);

            normalizedIds.add(Label.builder()
                .id(resolvedId)
                .name(label.getName())
                .color(label.getColor())
                .description(label.getDescription())
                .build());
        }

        if (normalizedIds.size() > MAX_LABELS) {
            return new ArrayList<>(normalizedIds.subList(0, MAX_LABELS));
        }

        return normalizedIds;
    }

    private Label normalizeLabel(Label label) {
        String normalizedId = sanitizeId(label.getId());
        if (normalizedId == null || normalizedId.isBlank()) {
            normalizedId = UUID.randomUUID().toString();
        }

        String normalizedColor = label.getColor() != null && !label.getColor().isBlank()
                                 ? label.getColor().trim()
                                 : "#6b7280";
        if (!HEX_COLOR_PATTERN.matcher(normalizedColor).matches()) {
            normalizedColor = "#6b7280";
        }
        normalizedColor = normalizedColor.toLowerCase(Locale.ROOT);

        String normalizedName = truncate(label.getName() != null ? label.getName().trim() : "", MAX_LABEL_NAME_LENGTH);
        String normalizedDescription = label.getDescription() != null
                                       ? truncate(label.getDescription().trim(), MAX_LABEL_DESCRIPTION_LENGTH)
                                       : null;

        return Label.builder()
            .id(normalizedId)
            .name(normalizedName)
            .color(normalizedColor)
            .description(normalizedDescription)
            .build();
    }

    private String sanitizeId(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.length() <= MAX_LABEL_ID_LENGTH) {
            return trimmed;
        }

        return trimmed.substring(0, MAX_LABEL_ID_LENGTH);
    }

    private Label mapToLabel(Map<String, Object> map) {
        return normalizeLabel(Label.builder()
            .id(map.get("id") != null ? map.get("id").toString() : null)
            .name(map.get("name") != null ? map.get("name").toString() : "")
            .color(map.get("color") != null ? map.get("color").toString() : "#6b7280")
            .description(map.get("description") != null ? map.get("description").toString() : null)
            .build());
    }

    private Map<String, Object> labelToMap(Label label) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", label.getId());
        map.put("name", label.getName());
        map.put("color", label.getColor());
        if (label.getDescription() != null) {
            map.put("description", label.getDescription());
        }
        return map;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
