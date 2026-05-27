package gg.modl.backend.settings.dto.request;

import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.settings.data.AIModerationSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public record UpdateAIModerationSettingsRequest(
    Boolean enableAIReview,
    Boolean enableAutomatedActions,
    @Size(max = RequestValidationLimits.AI_PUNISHMENT_CONFIGS_MAX_ENTRIES)
    Map<
        String,
        @Valid AIPunishmentConfigRequest
        > aiPunishmentConfigs
) {
    public AIModerationSettings toSettings() {
        return AIModerationSettings.builder()
            .enableAIReview(Boolean.TRUE.equals(enableAIReview))
            .enableAutomatedActions(Boolean.TRUE.equals(enableAutomatedActions))
            .aiPunishmentConfigs(toPunishmentConfigs())
            .build();
    }

    private Map<String, AIModerationSettings.AIPunishmentConfig> toPunishmentConfigs() {
        if (aiPunishmentConfigs == null) {
            return null;
        }

        return aiPunishmentConfigs.entrySet()
            .stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue() != null ? entry.getValue().toConfig() : null,
                (left, right) -> right,
                LinkedHashMap::new
            ));
    }

    public record AIPunishmentConfigRequest(
        @NotBlank
        @Size(max = RequestValidationLimits.AI_PUNISHMENT_ID_MAX_LENGTH)
        String id,
        @NotBlank
        @Size(max = RequestValidationLimits.AI_PUNISHMENT_NAME_MAX_LENGTH)
        String name,
        @NotBlank
        @Size(max = RequestValidationLimits.AI_PUNISHMENT_DESCRIPTION_MAX_LENGTH)
        String aiDescription,
        Boolean enabled
    ) {
        public AIModerationSettings.AIPunishmentConfig toConfig() {
            return AIModerationSettings.AIPunishmentConfig.builder()
                .id(id)
                .name(name)
                .aiDescription(aiDescription)
                .enabled(Boolean.TRUE.equals(enabled))
                .build();
        }
    }
}
