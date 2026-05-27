package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.EnforcementCategory;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlayerStatusCalculator {
    private final PunishmentTypeService punishmentTypeService;
    private final OffenderThresholdSettingsService offenderThresholdSettingsService;

    public PlayerStatus calculateStatus(Server server, List<Punishment> punishments) {
        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        Map<Integer, PunishmentType> typesByOrdinal = PunishmentTypeIndex.byOrdinal(types);
        OffenderThresholdSettings thresholdSettings = offenderThresholdSettingsService.getThresholdSettings(server);
        long socialExpiryMs = thresholdSettings.getSocial().getPointExpiryMs();
        long gameplayExpiryMs = thresholdSettings.getGameplay().getPointExpiryMs();
        long now = System.currentTimeMillis();

        int socialPoints = 0;
        int gameplayPoints = 0;

        for (Punishment punishment : punishments) {
            if (!isPunishmentActive(punishment)) {
                continue;
            }

            int typeOrdinal = punishment.getTypeOrdinal();
            Map<String, Object> data = punishment.getData();
            String severity = PunishmentData.getSeverity(data);

            PunishmentType type = typesByOrdinal.get(typeOrdinal);
            if (type == null) {
                continue;
            }

            // Check if this punishment's points have expired
            Date effectiveExpiry = getEffectiveExpiry(punishment);
            if (effectiveExpiry != null) {
                long expiryMs = type.isSocial() ? socialExpiryMs : gameplayExpiryMs;
                if (effectiveExpiry.getTime() + expiryMs < now) {
                    continue; // Points have expired past the configured window
                }
            }

            int points = type.getPointsForSeverity(severity != null ? severity : "regular");

            if (type.isSocial()) {
                socialPoints += points;
            } else if (type.isGameplay()) {
                gameplayPoints += points;
            }
        }

        String socialStatus = getStatusFromPoints(socialPoints);
        String gameplayStatus = getStatusFromPoints(gameplayPoints);

        return new PlayerStatus(socialStatus, gameplayStatus, socialPoints, gameplayPoints);
    }

    public boolean isPunishmentActive(Punishment punishment) {
        // Kicks (ordinal 0) are instant and never considered "active"
        if (punishment.getTypeOrdinal() == 0) {
            return false;
        }

        Map<String, Object> data = punishment.getData();
        if (data == null) {
            return false;
        }

        String status = PunishmentData.getStatus(data);
        if (PunishmentStatus.UNSTARTED.equals(status)) {
            return false;
        }

        for (PunishmentModification mod : punishment.getModifications()) {
            String type = mod.type();
            if (PunishmentModificationType.isPardon(type)) {
                return false;
            }
        }

        Date effectiveExpiry = getEffectiveExpiry(punishment);
        return effectiveExpiry == null || !effectiveExpiry.before(new Date());
    }

    public Date getEffectiveExpiry(Punishment punishment) {
        Map<String, Object> data = punishment.getData();
        if (data == null) {
            return null;
        }

        Long duration = null;
        Date durationBase = null;
        for (PunishmentModification mod : punishment.getModifications()) {
            if (mod.effectiveDuration() != null) {
                duration = mod.effectiveDuration();
                durationBase = mod.date();
            }
        }

        if (duration == null) {
            duration = PunishmentData.getDuration(data);
        }

        // null, 0, or negative (-1L) indicates permanent (no expiry)
        if (duration == null || duration <= 0) {
            return null;
        }

        // If duration came from a modification, count from the modification date
        if (durationBase != null) {
            return new Date(durationBase.getTime() + duration);
        }

        // Count from started date; unstarted punishments use current time so the plugin
        // receives a proper expiration for display — nothing is persisted until acknowledged.
        Date baseDate = punishment.getStarted() != null ? punishment.getStarted() : new Date();
        return new Date(baseDate.getTime() + duration);
    }

    private String getStatusFromPoints(int points) {
        if (points == 0) {
            return "Good";
        } else if (points <= 2) {
            return "Warning";
        } else if (points <= 5) {
            return "Restricted";
        } else {
            return "Banned";
        }
    }

    /**
     * Determine the effective enforcement category for a punishment.
     * Core types use isBan()/isMute()/isKick().
     * Social/gameplay types use the DurationDetail for the stored severity and offense level.
     *
     * @return EnforcementCategory name, or null (for kicks and unknown types)
     */
    public String getEffectiveCategory(Punishment punishment, List<PunishmentType> types) {
        return getEffectiveCategory(punishment, PunishmentTypeIndex.byOrdinal(types));
    }

    public String getEffectiveCategory(Punishment punishment, Map<Integer, PunishmentType> typesByOrdinal) {
        PunishmentType pt = typesByOrdinal.get(punishment.getTypeOrdinal());
        return getEffectiveCategory(pt, punishment.getData());
    }

    /**
     * Determine the effective enforcement category for a punishment type with given data.
     *
     * @return EnforcementCategory name, or null
     */
    public String getEffectiveCategory(PunishmentType pt, Map<String, Object> data) {
        if (pt == null) {
            return null;
        }
        if (pt.isKick()) {
            return null;
        }
        if (pt.isBan()) {
            return EnforcementCategory.BAN.name();
        }
        if (pt.isMute()) {
            return EnforcementCategory.MUTE.name();
        }

        // Social/gameplay types: determine from DurationDetail
        if (data != null) {
            String severity = PunishmentData.getSeverity(data) != null ? PunishmentData.getSeverity(data) : "regular";
            String offenseLevel;
            String rawOffenseLevel = PunishmentData.getOffenseLevel(data);
            if (rawOffenseLevel != null) {
                offenseLevel = rawOffenseLevel;
            } else {
                String statusVal = PunishmentData.getStatus(data) != null ? PunishmentData.getStatus(data).toLowerCase(Locale.ROOT) : "";
                offenseLevel = switch (statusVal) {
                    case "low" -> "first";
                    case "medium" -> "medium";
                    case "habitual" -> "habitual";
                    default -> "first";
                };
            }
            DurationDetail detail = pt.getDurationDetail(severity, offenseLevel);
            if (detail != null) {
                if (detail.isBan()) {
                    return EnforcementCategory.BAN.name();
                }
                if (detail.isMute()) {
                    return EnforcementCategory.MUTE.name();
                }
            }
        }
        return null;
    }

    /**
     * Returns true when a punishment has naturally expired (not pardoned, not permanent, not a kick).
     * Used to detect punishments eligible for stat-wipe execution.
     */
    public boolean isPunishmentNaturallyExpired(Punishment punishment) {
        if (punishment.getStarted() == null) {
            return false;
        }
        if (punishment.getTypeOrdinal() == 0) {
            return false;
        }
        for (PunishmentModification mod : punishment.getModifications()) {
            if (PunishmentModificationType.isPardon(mod.type())) {
                return false;
            }
        }
        Date effectiveExpiry = getEffectiveExpiry(punishment);
        if (effectiveExpiry == null) {
            return false;
        }
        return effectiveExpiry.before(new Date());
    }

    public record PlayerStatus(
        String social,
        String gameplay,
        int socialPoints,
        int gameplayPoints
    ) {
    }
}
