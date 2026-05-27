package gg.modl.backend.storage.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.billing.service.UsageTrackingService;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageQuotaServiceTest {

    @Test
    void canUploadUsesLiveS3UsageAndCustomPremiumLimit() {
        StorageMetadataService metadataService = mock(StorageMetadataService.class);
        S3StorageService s3StorageService = mock(S3StorageService.class);
        UsageTrackingService usageTrackingService = mock(UsageTrackingService.class);
        StorageQuotaService service = new StorageQuotaService(
            metadataService,
            s3StorageService,
            usageTrackingService,
            mock(ServerMongoRepository.class)
        );
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setMaxStorageLimitBytes(10_000L);
        when(s3StorageService.calculateStorageUsed(server)).thenReturn(9_000L);

        assertTrue(service.canUpload(server, 1_000L));
        assertFalse(service.canUpload(server, 1_001L));
    }

    @Test
    void canUploadFallsBackToDefaultPremiumLimit() {
        StorageQuotaService service = new StorageQuotaService(
            mock(StorageMetadataService.class),
            mock(S3StorageService.class),
            mock(UsageTrackingService.class),
            mock(ServerMongoRepository.class)
        );
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);

        assertTrue(service.canUpload(server, 200L * 1024 * 1024 * 1024));
    }

    @Test
    void confirmAndRecordFileReservesQuotaBeforeRecordingMetadata() {
        StorageMetadataService metadataService = mock(StorageMetadataService.class);
        S3StorageService s3StorageService = mock(S3StorageService.class);
        UsageTrackingService usageTrackingService = mock(UsageTrackingService.class);
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        StorageQuotaService service = new StorageQuotaService(
            metadataService,
            s3StorageService,
            usageTrackingService,
            serverRepository
        );
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setId("server-id");
        server.setMaxStorageLimitBytes(10_000L);
        server.setStorageUsedBytes(9_000L);
        when(serverRepository.tryIncrementStorageUsedWithinLimit("server-id", 1_000L, 10_000L)).thenReturn(true);
        when(metadataService.recordReservedFile(server, "db/evidence/file.png", 1_000L, "image/png"))
            .thenReturn(StorageMetadataService.RecordFileResult.INSERTED);

        assertTrue(service.confirmAndRecordFile(server, "db/evidence/file.png", 1_000L, "image/png"));
        verify(metadataService).recordReservedFile(server, "db/evidence/file.png", 1_000L, "image/png");
    }

    @Test
    void confirmAndRecordFileRejectsWhenAtomicReservationFails() {
        StorageMetadataService metadataService = mock(StorageMetadataService.class);
        S3StorageService s3StorageService = mock(S3StorageService.class);
        UsageTrackingService usageTrackingService = mock(UsageTrackingService.class);
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        StorageQuotaService service = new StorageQuotaService(
            metadataService,
            s3StorageService,
            usageTrackingService,
            serverRepository
        );
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setId("server-id");
        server.setMaxStorageLimitBytes(10_000L);
        server.setStorageUsedBytes(9_500L);
        when(serverRepository.tryIncrementStorageUsedWithinLimit("server-id", 1_000L, 10_000L)).thenReturn(false);

        assertFalse(service.confirmAndRecordFile(server, "db/evidence/file.png", 1_000L, "image/png"));
    }

    @Test
    void confirmAndRecordFileRollsBackReservationWhenMetadataAlreadyExistsAfterRace() {
        StorageMetadataService metadataService = mock(StorageMetadataService.class);
        S3StorageService s3StorageService = mock(S3StorageService.class);
        UsageTrackingService usageTrackingService = mock(UsageTrackingService.class);
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        StorageQuotaService service = new StorageQuotaService(
            metadataService,
            s3StorageService,
            usageTrackingService,
            serverRepository
        );
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setId("server-id");
        server.setMaxStorageLimitBytes(10_000L);
        server.setStorageUsedBytes(9_000L);
        when(serverRepository.tryIncrementStorageUsedWithinLimit("server-id", 1_000L, 10_000L)).thenReturn(true);
        when(metadataService.recordReservedFile(server, "db/evidence/file.png", 1_000L, "image/png"))
            .thenReturn(StorageMetadataService.RecordFileResult.ALREADY_EXISTS);

        assertTrue(service.confirmAndRecordFile(server, "db/evidence/file.png", 1_000L, "image/png"));
        verify(serverRepository).decrementStorageUsed("server-id", 1_000L);
    }
}
