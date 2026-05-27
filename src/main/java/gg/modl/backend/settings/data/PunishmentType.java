package gg.modl.backend.settings.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PunishmentType {
    private Integer id;
    private String name;
    private String category;
    private Boolean customizable;
    private Integer ordinal;

    private PunishmentDurations durations;

    private Boolean singleSeverityPunishment;
    private OffenseLevelDurations singleSeverityDurations;
    private Integer singleSeverityPoints;

    private PunishmentPoints points;
    private Integer customPoints;

    private String staffDescription;
    private String playerDescription;

    private Boolean canBeAltBlocking;
    private Boolean canBeStatWiping;
    private Boolean appealable;

    private AppealForm appealForm;

    private Boolean permanentUntilSkinChange;
    private Boolean permanentUntilUsernameChange;

    public boolean isSocial() {
        return "Social".equalsIgnoreCase(category);
    }

    public boolean isGameplay() {
        return "Gameplay".equalsIgnoreCase(category);
    }

    public boolean isAdministrative() {
        return "Administrative".equalsIgnoreCase(category);
    }

    public boolean isBan() {
        // Core types (ordinals 0-5) are hardcoded
        if (ordinal != null && ordinal >= 0 && ordinal <= 5) {
            return ordinal >= 2;
        }
        return category != null && category.toLowerCase(Locale.ROOT).contains("ban");
    }

    public boolean isMute() {
        if (ordinal != null && ordinal >= 0 && ordinal <= 5) {
            return ordinal == 1;
        }
        return category != null && category.toLowerCase(Locale.ROOT).contains("mute");
    }

    public boolean isKick() {
        if (ordinal != null && ordinal >= 0 && ordinal <= 5) {
            return ordinal == 0;
        }
        return category != null && category.toLowerCase(Locale.ROOT).contains("kick");
    }

    public int getPointsForSeverity(String severity) {
        if (customPoints != null) {
            return customPoints;
        }
        if (singleSeverityPoints != null) {
            return singleSeverityPoints;
        }
        if (points != null) {
            return points.getForSeverity(severity);
        }
        return 0;
    }

    public long getDurationMillis(String severity, String offenseLevel) {
        if (isSingleSeverityPunishment() && singleSeverityDurations != null) {
            DurationDetail detail = singleSeverityDurations.getForOffenseLevel(offenseLevel);
            return detail != null ? detail.toMilliseconds() : 0L;
        }
        if (durations != null) {
            DurationDetail detail = durations.getDuration(severity, offenseLevel);
            return detail != null ? detail.toMilliseconds() : 0L;
        }
        return 0L;
    }

    public boolean isSingleSeverityPunishment() {
        return singleSeverityPunishment != null && singleSeverityPunishment;
    }

    public DurationDetail getDurationDetail(String severity, String offenseLevel) {
        if (isSingleSeverityPunishment() && singleSeverityDurations != null) {
            return singleSeverityDurations.getForOffenseLevel(offenseLevel);
        }
        if (durations != null) {
            return durations.getDuration(severity, offenseLevel);
        }
        return null;
    }

    public boolean isCustomizable() {
        return customizable != null && customizable;
    }

    public boolean isCanBeAltBlocking() {
        return canBeAltBlocking != null && canBeAltBlocking;
    }

    public boolean isCanBeStatWiping() {
        return canBeStatWiping != null && canBeStatWiping;
    }

    public boolean isAppealable() {
        return appealable != null && appealable;
    }

    public boolean isPermanentUntilSkinChange() {
        return permanentUntilSkinChange != null && permanentUntilSkinChange;
    }

    public boolean isPermanentUntilUsernameChange() {
        return permanentUntilUsernameChange != null && permanentUntilUsernameChange;
    }
}
