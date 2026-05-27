package gg.modl.backend.migration.service;

import gg.modl.backend.database.mongo.repository.MigrationMongoRepository;
import gg.modl.backend.infrastructure.exception.ExternalServiceException;
import gg.modl.backend.migration.config.MigrationConfiguration;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.dto.MigrationStatusResponse;
import gg.modl.backend.migration.dto.UpdateProgressRequest;
import gg.modl.backend.realtime.dispatch.RealtimeEventDispatcher;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.MigrationStatusChangedEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.Topic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationService {
    private final MigrationMongoRepository migrationRepository;
    private final MigrationConfiguration migrationConfiguration;
    private final RealtimeEventDispatcher realtimeEventDispatcher;

    private static final List<String> VALID_TYPES = List.of("litebans");
    private static final List<String> VALID_STATUSES = List.of(
        "idle", "building_json", "uploading_json", "processing_data", "completed", "failed"
    );
    private static final long COOLDOWN_MS = 60 * 60 * 1000;
    private static final long DEFAULT_FILE_SIZE_LIMIT = 500 * 1024 * 1024;
    private static final int MAX_MESSAGE_LENGTH = 1000;

    public MigrationStatusResponse getMigrationStatus(Server server) {
        MigrationStatus status = migrationRepository.findLatest(server).orElse(null);

        MigrationStatusResponse.CurrentMigration currentMigration = null;
        if (status != null) {
            currentMigration = new MigrationStatusResponse.CurrentMigration(
                status.getTaskId(),
                status.getType(),
                status.getStatus(),
                status.getProgress(),
                status.getStartedAt(),
                status.getCompletedAt(),
                status.getError()
            );
        }

        MigrationStatusResponse.CooldownInfo cooldown = checkCooldown(server);

        return new MigrationStatusResponse(currentMigration, cooldown);
    }

    public MigrationStatusResponse.CooldownInfo checkCooldown(Server server) {
        MigrationStatus lastMigration = migrationRepository.findLatestCompletedOrFailed(server).orElse(null);

        if (lastMigration == null || lastMigration.getCompletedAt() == null) {
            return new MigrationStatusResponse.CooldownInfo(false, null);
        }

        long timeSinceCompletion = System.currentTimeMillis() - lastMigration.getCompletedAt().getTime();

        if (timeSinceCompletion < COOLDOWN_MS) {
            return new MigrationStatusResponse.CooldownInfo(true, COOLDOWN_MS - timeSinceCompletion);
        }

        return new MigrationStatusResponse.CooldownInfo(false, null);
    }

    public Map<String, Object> startMigration(Server server, String migrationType) {
        if (!VALID_TYPES.contains(migrationType.toLowerCase(Locale.ROOT))) {
            return Map.of("success", false, "error", "Invalid migration type");
        }

        if (migrationRepository.existsActiveMigration(server)) {
            return Map.of("success", false, "error", "A migration is already in progress");
        }

        MigrationStatusResponse.CooldownInfo cooldown = checkCooldown(server);
        if (cooldown.onCooldown()) {
            return Map.of("success", false, "error", "Migration on cooldown. Please wait before starting another migration.");
        }

        String taskId = UUID.randomUUID().toString();
        Date now = new Date();

        MigrationStatus status = MigrationStatus.builder()
            .taskId(taskId)
            .type(migrationType.toLowerCase(Locale.ROOT))
            .status("building_json")
            .progress(MigrationStatus.MigrationProgress.builder()
                .message("Waiting for Minecraft server to build migration file...")
                .recordsProcessed(0)
                .recordsSkipped(0)
                .build())
            .startedAt(now)
            .build();

        migrationRepository.saveEntity(server, status);
        publishMigrationStatusChanged(server, taskId, status.getStatus());

        return Map.of(
            "success", true,
            "taskId", taskId,
            "message", "Migration task initiated. Waiting for Minecraft server to process."
        );
    }

    public Map<String, Object> cancelMigration(Server server) {
        MigrationStatus activeMigration = migrationRepository.findActiveMigration(server).orElse(null);

        if (activeMigration == null) {
            return Map.of("success", false, "error", "No active migration to cancel");
        }

        migrationRepository.cancelMigration(server, activeMigration.getId(),
            "Cancelled by administrator", new Date(), "Migration cancelled by administrator");
        publishMigrationStatusChanged(server, activeMigration.getTaskId(), "cancelled");

        return Map.of("success", true, "message", "Migration cancelled successfully");
    }

    public Map<String, Object> validateFileSize(Server server, MultipartFile file) {
        long fileSizeLimit = getFileSizeLimit(server);
        if (file.getSize() <= fileSizeLimit) {
            return null;
        }

        double fileSizeMB = file.getSize() / (1024.0 * 1024.0);
        double limitMB = fileSizeLimit / (1024.0 * 1024.0);

        updateProgress(server, new UpdateProgressRequest(
            "failed", "Migration file exceeds size limit", 0, 0, null
        ));

        return Map.of(
            "error", "Migration file exceeds size limit",
            "message", String.format("File size (%.2fMB) exceeds the limit of %.2fMB.", fileSizeMB, limitMB),
            "fileSize", file.getSize(),
            "limit", fileSizeLimit
        );
    }

    public Map<String, Object> updateProgress(Server server, UpdateProgressRequest request) {
        if (request.status() == null || !VALID_STATUSES.contains(request.status())) {
            return Map.of("success", false, "error", "Invalid status value");
        }

        if (request.message() == null || request.message().length() > MAX_MESSAGE_LENGTH) {
            return Map.of("success", false, "error", "Invalid or too long message");
        }

        if (request.recordsProcessed() != null && request.recordsProcessed() < 0) {
            return Map.of("success", false, "error", "Invalid recordsProcessed value");
        }

        if (request.totalRecords() != null && request.totalRecords() < 0) {
            return Map.of("success", false, "error", "Invalid totalRecords value");
        }

        MigrationStatus activeMigration = migrationRepository.findActiveMigration(server).orElse(null);

        if (activeMigration == null) {
            return Map.of("success", false, "error", "No active migration found");
        }

        Date completedAt = ("completed".equals(request.status()) || "failed".equals(request.status()))
            ? new Date() : null;

        migrationRepository.updateProgress(server, activeMigration.getId(),
            request.status(), request.message(),
            request.recordsProcessed(), request.recordsSkipped(),
            request.totalRecords(), completedAt);
        publishMigrationStatusChanged(server, activeMigration.getTaskId(), request.status());

        return Map.of("success", true);
    }

    public long getFileSizeLimit(Server server) {
        if (server.getMigrationFileSizeLimit() != null) {
            return server.getMigrationFileSizeLimit();
        }
        return DEFAULT_FILE_SIZE_LIMIT;
    }

    public Path saveUploadedFile(MultipartFile file) {
        try {
            Path uploadPath = Paths.get(migrationConfiguration.getUploadDir()).toAbsolutePath();
            Files.createDirectories(uploadPath);
            String uniqueFilename = "migration-" + UUID.randomUUID() + ".json";
            Path filePath = uploadPath.resolve(uniqueFilename);
            file.transferTo(filePath.toAbsolutePath());
            return filePath;
        } catch (IOException e) {
            throw new ExternalServiceException("Failed to save migration file", e);
        }
    }

    private void publishMigrationStatusChanged(Server server, String taskId, String status) {
        try {
            MigrationStatusChangedEvent.Builder payload = MigrationStatusChangedEvent.newBuilder().setMigrationId(taskId);
            if (status != null) {
                payload.setStatus(status);
            }
            realtimeEventDispatcher.publish(new RealtimeOutboundEvent(
                server.getId(),
                Topic.TOPIC_PANEL_MIGRATIONS,
                RealtimeEnvelope.newBuilder()
                    .setEventId(server.getId() + "::migration::" + taskId + "::" + System.nanoTime())
                    .setMigrationStatusChanged(payload)
                    .build()
            ));
        } catch (RuntimeException ex) {
            log.warn("realtime publish failed for migration {} status {}", taskId, status, ex);
        }
    }
}
