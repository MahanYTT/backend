package gg.modl.backend.settings.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OffenderThresholdSettings {
    @Builder.Default
    private CategoryThresholds social = new CategoryThresholds(4, 8);

    @Builder.Default
    private CategoryThresholds gameplay = new CategoryThresholds(5, 10);

    public static OffenderThresholdSettings defaults() {
        return OffenderThresholdSettings.builder().build();
    }

    public String getSocialOffenderLevel(int points) {
        return social.getOffenderLevel(points);
    }

    public String getGameplayOffenderLevel(int points) {
        return gameplay.getOffenderLevel(points);
    }

    public String getOffenseLevelInternal(int points, boolean isSocial) {
        return isSocial ? social.getOffenseLevelInternal(points) : gameplay.getOffenseLevelInternal(points);
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryThresholds {
        private int medium;
        private int habitual;
        // Default 24 months (2 years). Permanent punishments always count.
        private int pointExpiryMonths = 24;

        public CategoryThresholds(int medium, int habitual) {
            this.medium = medium;
            this.habitual = habitual;
            this.pointExpiryMonths = 24;
        }

        public CategoryThresholds(int medium, int habitual, int pointExpiryMonths) {
            this.medium = medium;
            this.habitual = habitual;
            this.pointExpiryMonths = pointExpiryMonths;
        }

        @JsonIgnore
        public long getPointExpiryMs() {
            return (long) pointExpiryMonths * 30L * 24L * 60L * 60L * 1000L;
        }

        public String getOffenderLevel(int points) {
            if (points >= habitual) {
                return "Habitual";
            } else if (points >= medium) {
                return "Medium";
            } else {
                return "Low";
            }
        }

        public String getOffenseLevelInternal(int points) {
            if (points >= habitual) {
                return "habitual";
            } else if (points >= medium) {
                return "medium";
            } else {
                return "first";
            }
        }
    }
}
