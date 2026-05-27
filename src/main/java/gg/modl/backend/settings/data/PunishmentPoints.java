package gg.modl.backend.settings.data;

import java.util.Locale;

public record PunishmentPoints(
    int low,
    int regular,
    int severe
) {
    public int getForSeverity(String severity) {
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "low" -> low;
            case "regular" -> regular;
            case "severe" -> severe;
            default -> regular;
        };
    }
}
