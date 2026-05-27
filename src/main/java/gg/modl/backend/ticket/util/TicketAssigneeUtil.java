package gg.modl.backend.ticket.util;

import gg.modl.backend.infrastructure.util.UuidUtil;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

@UtilityClass
public class TicketAssigneeUtil {
    public static final int MAX_ASSIGNEES = 20;

    @Nullable
    public static String normalizeUuid(@Nullable String value) {
        return UuidUtil.normalizeUuid(value);
    }

    public static List<String> normalizeCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return normalizeCollection(Arrays.asList(value.split(",")));
    }

    public static List<String> normalizeCollection(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String token = normalizeSingle(value);
            if (token == null) {
                continue;
            }
            normalized.add(token);
            if (normalized.size() >= MAX_ASSIGNEES) {
                break;
            }
        }

        if (normalized.isEmpty()) {
            return List.of();
        }

        return List.copyOf(normalized);
    }

    @Nullable
    public static String normalizeSingle(@Nullable String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed.toLowerCase(Locale.ROOT);
    }

    public static String toDisplayString(List<String> assignedTo) {
        if (assignedTo == null || assignedTo.isEmpty()) {
            return "";
        }
        return String.join(", ", assignedTo);
    }
}
