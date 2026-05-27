package gg.modl.backend.settings.data;

import java.util.Locale;

public record PunishmentDurations(
    OffenseLevelDurations low,
    OffenseLevelDurations regular,
    OffenseLevelDurations severe
) {
    public DurationDetail getDuration(String severity, String offenseLevel) {
        OffenseLevelDurations severityDurations = getForSeverity(severity);
        return severityDurations != null ? severityDurations.getForOffenseLevel(offenseLevel) : null;
    }

    public OffenseLevelDurations getForSeverity(String severity) {
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "low" -> low;
            case "regular" -> regular;
            case "severe" -> severe;
            default -> regular;
        };
    }
}
