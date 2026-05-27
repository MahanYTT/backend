package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentDurationCalculator {

    private final PunishmentTypeService punishmentTypeService;
    private final OffenderThresholdSettingsService thresholdSettingsService;
    private final PlayerStatusCalculator statusCalculator;

    public record DurationResult(@Nullable Long duration, @Nullable String status) {}

    public DurationResult calculate(Server server, List<Punishment> existingPunishments, int typeOrdinal, String severity) {
        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        PunishmentType punishmentType = types.stream()
            .filter(t -> t.getOrdinal() == typeOrdinal)
            .findFirst()
            .orElse(null);

        if (punishmentType == null) {
            return new DurationResult(null, null);
        }

        OffenderThresholdSettings thresholds = thresholdSettingsService.getThresholdSettings(server);
        PlayerStatusCalculator.PlayerStatus currentStatus = statusCalculator.calculateStatus(server, existingPunishments);

        boolean isSocial = punishmentType.isSocial();
        int relevantPoints = isSocial ? currentStatus.socialPoints() : currentStatus.gameplayPoints();
        String offenseLevel = thresholds.getOffenseLevelInternal(relevantPoints, isSocial);

        String internalSeverity = switch (severity.toLowerCase(Locale.ROOT)) {
            case "low", "lenient" -> "low";
            case "regular" -> "regular";
            case "aggravated", "severe" -> "severe";
            default -> "regular";
        };

        DurationDetail durationDetail = punishmentType.getDurationDetail(internalSeverity, offenseLevel);

        if (durationDetail == null) {
            PunishmentType defaultType = DefaultPunishmentTypes.getAll()
                .stream()
                .filter(t -> t.getOrdinal() == typeOrdinal)
                .findFirst()
                .orElse(null);
            if (defaultType != null) {
                durationDetail = defaultType.getDurationDetail(internalSeverity, offenseLevel);
            }
        }

        String displayStatus = switch (offenseLevel) {
            case "first" -> "low";
            default -> offenseLevel;
        };

        Long calculatedDuration = null;
        if (durationDetail != null) {
            long durationMs = durationDetail.toMilliseconds();
            if (durationMs != 0) {
                calculatedDuration = durationMs;
            }
        }

        return new DurationResult(calculatedDuration, displayStatus);
    }
}
