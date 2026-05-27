package gg.modl.backend.settings.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplayRetentionSettingsServiceTest {
    @Mock private SettingsDocumentService settingsDocumentService;
    private ReplayRetentionSettingsService service;
    private Server server;

    @BeforeEach
    void setUp() {
        service = new ReplayRetentionSettingsService(settingsDocumentService, new ObjectMapper());
        server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
    }

    @Test
    void getReplayRetentionSettingsDefaultsToEnabledSevenDays() {
        when(settingsDocumentService.getRawState(server, "replayRetention"))
            .thenReturn(new SettingsDocumentService.RawSettingsState(new LinkedHashMap<>(), 0L, null, false));

        VersionedSettings<ReplayRetentionSettings> settings = service.getReplayRetentionSettingsState(server);

        assertEquals(0L, settings.version());
        assertEquals(true, settings.data().isEnabled());
        assertEquals(7, settings.data().getDays());
    }

    @Test
    void patchReplayRetentionSettingsPersistsVersionedDataAndCapsDays() {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("enabled", true);
        current.put("days", 7);
        Date updatedAt = new Date(1000L);
        Map<String, Object> updated = new LinkedHashMap<>();
        updated.put("enabled", true);
        updated.put("days", 365);

        when(settingsDocumentService.getRawState(server, "replayRetention"))
            .thenReturn(new SettingsDocumentService.RawSettingsState(current, 2L, updatedAt, true));
        when(settingsDocumentService.saveRawState(server, "replayRetention", 2L, updated))
            .thenReturn(new SettingsDocumentService.RawSettingsState(updated, 3L, updatedAt, true));

        VersionedSettings<ReplayRetentionSettings> settings = service.patchReplayRetentionSettings(server, 2L, null, 400);

        assertEquals(3L, settings.version());
        assertEquals(true, settings.data().isEnabled());
        assertEquals(365, settings.data().getDays());
    }

    @Test
    void getReplayRetentionSettingsMergesPartialDataWithDefaults() {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("days", 14);

        when(settingsDocumentService.getRawState(server, "replayRetention"))
            .thenReturn(new SettingsDocumentService.RawSettingsState(current, 1L, null, true));

        VersionedSettings<ReplayRetentionSettings> settings = service.getReplayRetentionSettingsState(server);

        assertEquals(true, settings.data().isEnabled());
        assertEquals(14, settings.data().getDays());
    }

    @Test
    void patchReplayRetentionSettingsRejectsEnabledZeroDays() {
        when(settingsDocumentService.getRawState(server, "replayRetention"))
            .thenReturn(new SettingsDocumentService.RawSettingsState(new LinkedHashMap<>(), 0L, null, false));

        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> service.patchReplayRetentionSettings(server, 0L, true, 0)
        );

        assertEquals("Replay retention days must be at least 1 when enabled", exception.getMessage());
    }
}
