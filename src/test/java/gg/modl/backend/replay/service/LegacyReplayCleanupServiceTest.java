package gg.modl.backend.replay.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.ReplayMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.replay.config.LegacyReplayCleanupProperties;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import gg.modl.backend.settings.service.ReplayRetentionSettingsService;
import gg.modl.backend.storage.service.S3StorageService;
import gg.modl.backend.storage.service.StorageMetadataService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LegacyReplayCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-05-18T12:00:00Z");

    @Mock private ServerMongoRepository serverRepository;
    @Mock private ReplayMongoRepository replayRepository;
    @Mock private ReplayRetentionSettingsService replayRetentionSettingsService;
    @Mock private S3StorageService s3StorageService;
    @Mock private StorageMetadataService storageMetadataService;
    private LegacyReplayCleanupService cleanupService;
    private Server server;

    @BeforeEach
    void setUp() {
        LegacyReplayCleanupProperties properties = new LegacyReplayCleanupProperties();
        properties.setEnabled(true);
        properties.setBatchSize(100);
        cleanupService = new LegacyReplayCleanupService(
            properties,
            serverRepository,
            replayRepository,
            replayRetentionSettingsService,
            s3StorageService,
            storageMetadataService,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
    }

    @Test
    void cleanupDeletesExpiredReplayStorageMetadataAndRecord() {
        ReplayDocument replay = replay("replay-1", "db/replays/replay-1.modlreplay");
        Date cutoff = Date.from(NOW.minus(Duration.ofDays(7)));

        when(serverRepository.findAll()).thenReturn(List.of(server));
        when(replayRetentionSettingsService.getReplayRetentionSettings(server))
            .thenReturn(new ReplayRetentionSettings(true, 7));
        when(replayRepository.countExpiredWithMissingStorageKey(server, cutoff)).thenReturn(0L);
        when(replayRepository.findExpiredCompletedOrFailed(server, cutoff, 100)).thenReturn(List.of(replay));
        when(s3StorageService.deleteFile("db/replays/replay-1.modlreplay")).thenReturn(true);
        when(storageMetadataService.removeFile(server, "db/replays/replay-1.modlreplay")).thenReturn(true);
        when(replayRepository.deleteByReplayId(server, "replay-1")).thenReturn(true);

        cleanupService.runCleanupOnce();

        verify(s3StorageService).deleteFile("db/replays/replay-1.modlreplay");
        verify(storageMetadataService).removeFile(server, "db/replays/replay-1.modlreplay");
        verify(replayRepository).deleteByReplayId(server, "replay-1");
    }

    @Test
    void cleanupDoesNotRemoveMetadataOrReplayRecordWhenStorageDeleteFails() {
        ReplayDocument replay = replay("replay-1", "db/replays/replay-1.modlreplay");
        Date cutoff = Date.from(NOW.minus(Duration.ofDays(7)));

        when(serverRepository.findAll()).thenReturn(List.of(server));
        when(replayRetentionSettingsService.getReplayRetentionSettings(server))
            .thenReturn(new ReplayRetentionSettings(true, 7));
        when(replayRepository.countExpiredWithMissingStorageKey(server, cutoff)).thenReturn(0L);
        when(replayRepository.findExpiredCompletedOrFailed(server, cutoff, 100)).thenReturn(List.of(replay));
        when(s3StorageService.deleteFile("db/replays/replay-1.modlreplay")).thenReturn(false);

        cleanupService.runCleanupOnce();

        verify(storageMetadataService, never()).removeFile(server, "db/replays/replay-1.modlreplay");
        verify(replayRepository, never()).deleteByReplayId(server, "replay-1");
    }

    @Test
    void cleanupDoesNotRemoveReplayRecordWhenMetadataRemovalFails() {
        ReplayDocument replay = replay("replay-1", "db/replays/replay-1.modlreplay");
        Date cutoff = Date.from(NOW.minus(Duration.ofDays(7)));

        when(serverRepository.findAll()).thenReturn(List.of(server));
        when(replayRetentionSettingsService.getReplayRetentionSettings(server))
            .thenReturn(new ReplayRetentionSettings(true, 7));
        when(replayRepository.countExpiredWithMissingStorageKey(server, cutoff)).thenReturn(0L);
        when(replayRepository.findExpiredCompletedOrFailed(server, cutoff, 100)).thenReturn(List.of(replay));
        when(s3StorageService.deleteFile("db/replays/replay-1.modlreplay")).thenReturn(true);
        when(storageMetadataService.removeFile(server, "db/replays/replay-1.modlreplay")).thenReturn(false);

        cleanupService.runCleanupOnce();

        verify(storageMetadataService).removeFile(server, "db/replays/replay-1.modlreplay");
        verify(replayRepository, never()).deleteByReplayId(server, "replay-1");
    }

    @Test
    void cleanupReportsMissingStorageKeyCountAndDoesNotProcessBlankRecords() {
        Date cutoff = Date.from(NOW.minus(Duration.ofDays(7)));

        when(serverRepository.findAll()).thenReturn(List.of(server));
        when(replayRetentionSettingsService.getReplayRetentionSettings(server))
            .thenReturn(new ReplayRetentionSettings(true, 7));
        when(replayRepository.countExpiredWithMissingStorageKey(server, cutoff)).thenReturn(3L);
        when(replayRepository.findExpiredCompletedOrFailed(server, cutoff, 100)).thenReturn(List.of());

        cleanupService.runCleanupOnce();

        verify(replayRepository).countExpiredWithMissingStorageKey(server, cutoff);
        verify(s3StorageService, never()).deleteFile(any());
        verify(storageMetadataService, never()).removeFile(eq(server), any());
        verify(replayRepository, never()).deleteByReplayId(eq(server), any());
    }

    @Test
    void cleanupSkipsTenantWhenRetentionIsDisabled() {
        when(serverRepository.findAll()).thenReturn(List.of(server));
        when(replayRetentionSettingsService.getReplayRetentionSettings(server))
            .thenReturn(new ReplayRetentionSettings(false, 7));

        cleanupService.runCleanupOnce();

        verify(replayRepository, never()).countExpiredWithMissingStorageKey(eq(server), any(Date.class));
        verify(replayRepository, never()).findExpiredCompletedOrFailed(
            eq(server),
            any(Date.class),
            anyInt()
        );
        verify(s3StorageService, never()).deleteFile(any());
        verify(storageMetadataService, never()).removeFile(
            eq(server),
            any()
        );
    }

    private ReplayDocument replay(String id, String storageKey) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId(id);
        replay.setStorageKey(storageKey);
        replay.setStatus(ReplayDocument.STATUS_COMPLETE);
        replay.setCreatedAt(Date.from(NOW.minus(Duration.ofDays(8))));
        return replay;
    }
}
