package gg.modl.backend.settings.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DurationDetail(
    int value,
    String unit,
    String type
) {
    public long toMilliseconds() {
        if (isPermanent()) {
            return -1L;
        }
        if (unit == null || unit.isEmpty()) {
            return -1L;
        }
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "seconds", "second" -> value * 1000L;
            case "minutes", "minute" -> value * 60L * 1000L;
            case "hours", "hour" -> value * 60L * 60L * 1000L;
            case "days", "day" -> value * 24L * 60L * 60L * 1000L;
            case "weeks", "week" -> value * 7L * 24L * 60L * 60L * 1000L;
            case "months", "month" -> value * 30L * 24L * 60L * 60L * 1000L;
            default -> -1L;
        };
    }

    public boolean isPermanent() {
        return "permanent ban".equals(type) || "permanent mute".equals(type);
    }

    public boolean isBan() {
        return "ban".equals(type) || "permanent ban".equals(type);
    }

    public boolean isMute() {
        return "mute".equals(type) || "permanent mute".equals(type);
    }
}
