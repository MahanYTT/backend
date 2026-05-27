package gg.modl.backend.settings.data;

import java.util.Locale;

public record OffenseLevelDurations(
    DurationDetail first,
    DurationDetail medium,
    DurationDetail habitual
) {
    public DurationDetail getForOffenseLevel(String offenseLevel) {
        return switch (offenseLevel.toLowerCase(Locale.ROOT)) {
            case "first" -> first;
            case "medium" -> medium;
            case "habitual" -> habitual;
            default -> first;
        };
    }
}
