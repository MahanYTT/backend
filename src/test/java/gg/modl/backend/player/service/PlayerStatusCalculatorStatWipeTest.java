package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlayerStatusCalculatorStatWipeTest {

    @Mock private PunishmentTypeService punishmentTypeService;
    @Mock private OffenderThresholdSettingsService offenderThresholdSettingsService;
    private PlayerStatusCalculator calculator;

    @BeforeEach
    void setUp() {
        // isPunishmentNaturallyExpired doesn't use these services
        calculator = new PlayerStatusCalculator(punishmentTypeService, offenderThresholdSettingsService);
    }

    @Test
    void expiredPunishmentReturnsTrue() {
        // Started 2 hours ago with 1 hour duration -> expired 1 hour ago
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        Punishment p = createPunishment(2, started, 3600_000L, List.of());
        assertTrue(calculator.isPunishmentNaturallyExpired(p));
    }

    private Punishment createPunishment(int typeOrdinal, Date started, long durationMs, List<PunishmentModification> modifications) {
        Map<String, Object> data = new HashMap<>();
        data.put("duration", durationMs);
        return new Punishment(
            "test-id", typeOrdinal, "TestIssuer", null,
            new Date(), started, modifications,
            List.of(), List.of(), List.of(), data
        );
    }

    @Test
    void activePunishmentReturnsFalse() {
        // Started 30 minutes ago with 1 hour duration -> still active
        Date started = new Date(System.currentTimeMillis() - 1800_000L);
        Punishment p = createPunishment(2, started, 3600_000L, List.of());
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void notStartedReturnsFalse() {
        Punishment p = createPunishment(2, null, 3600_000L, List.of());
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void kickReturnsFalse() {
        // Kicks (ordinal 0) should never be considered naturally expired
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        Punishment p = createPunishment(0, started, 3600_000L, List.of());
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void permanentPunishmentReturnsFalse() {
        // Permanent punishment (duration -1) never expires naturally
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        Punishment p = createPunishment(2, started, -1L, List.of());
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void zeroDurationPunishmentReturnsFalse() {
        // Zero duration treated as permanent
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        Punishment p = createPunishment(2, started, 0L, List.of());
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void pardonedPunishmentReturnsFalse() {
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        PunishmentModification pardon = new PunishmentModification(
            "mod-1", "MANUAL_PARDON", new Date(), "Staff", null, "", null, null, null
        );
        Punishment p = createPunishment(2, started, 3600_000L, List.of(pardon));
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void appealAcceptedReturnsFalse() {
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        PunishmentModification appealAccept = new PunishmentModification(
            "mod-1", "APPEAL_ACCEPT", new Date(), "Staff", null, "", null, null, null
        );
        Punishment p = createPunishment(2, started, 3600_000L, List.of(appealAccept));
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void systemPardonedReturnsFalse() {
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        PunishmentModification systemPardon = new PunishmentModification(
            "mod-1", "SYSTEM_PARDON", new Date(), "System", null, "", null, null, null
        );
        Punishment p = createPunishment(2, started, 3600_000L, List.of(systemPardon));
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void durationChangeExtendsPunishment() {
        // Started 2 hours ago with original 1 hour duration, but duration was changed to 4 hours 1 hour ago
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        Date modDate = new Date(System.currentTimeMillis() - 3600_000L);
        PunishmentModification durationChange = new PunishmentModification(
            "mod-1", "MANUAL_DURATION_CHANGE", modDate, "Staff", null, "Extended",
            10800_000L, // 3 hours from modification date -> expires 2 hours from now
            null, null
        );
        Punishment p = createPunishment(2, started, 3600_000L, List.of(durationChange));
        // Effective expiry = modDate + 3 hours = 2 hours from now -> still active
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void durationChangeShortensPunishmentToExpired() {
        // Started 2 hours ago, duration changed 1 hour ago to 30 minutes (expired 30 min ago)
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        Date modDate = new Date(System.currentTimeMillis() - 3600_000L);
        PunishmentModification durationChange = new PunishmentModification(
            "mod-1", "MANUAL_DURATION_CHANGE", modDate, "Staff", null, "Shortened",
            1800_000L, // 30 min from modification date -> expired 30 min ago
            null, null
        );
        Punishment p = createPunishment(2, started, 86400_000L, List.of(durationChange));
        assertTrue(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void mutePunishmentCanExpireNaturally() {
        // Mutes (ordinal 1) should also be eligible for natural expiry
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        Punishment p = createPunishment(1, started, 3600_000L, List.of());
        assertTrue(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void socialPunishmentCanExpireNaturally() {
        // Social punishment (ordinal 6 = Chat Abuse) can expire naturally
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        Punishment p = createPunishment(6, started, 3600_000L, List.of());
        assertTrue(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void gameplayPunishmentCanExpireNaturally() {
        // Gameplay punishment (ordinal 14 = Cheating) can expire naturally
        Date started = new Date(System.currentTimeMillis() - 7200_000L);
        Punishment p = createPunishment(14, started, 3600_000L, List.of());
        assertTrue(calculator.isPunishmentNaturallyExpired(p));
    }

    @Test
    void nullDataReturnsFalse() {
        // Punishment with null data -> getEffectiveExpiry returns null -> permanent -> false
        Punishment p = new Punishment(
            "test-id", 2, "TestIssuer", null,
            new Date(), new Date(System.currentTimeMillis() - 7200_000L),
            List.of(), List.of(), List.of(), List.of(),
            null
        );
        assertFalse(calculator.isPunishmentNaturallyExpired(p));
    }
}
