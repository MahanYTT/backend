package gg.modl.backend.storage.service;

import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.infrastructure.util.UuidUtil;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.service.PlayerDataUtils;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.player.service.PunishmentQueryService.UploadedEvidenceItem;
import gg.modl.backend.server.ServerService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.storage.dto.request.EvidenceConfirmUploadRequest;
import gg.modl.backend.storage.dto.request.EvidenceItemRequest;
import gg.modl.backend.storage.dto.request.EvidencePresignUploadRequest;
import gg.modl.backend.storage.dto.request.SubmitEvidenceRequest;
import gg.modl.backend.storage.dto.response.PresignUploadResponse;
import gg.modl.backend.storage.dto.response.UploadResponse;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EvidenceUploadService {
    private final EvidenceUploadTokenService tokenService;
    private final S3StorageService s3StorageService;
    private final PlayerMongoRepository playerRepository;
    private final ServerService serverService;
    private final StorageQuotaService quotaService;
    private final MediaValidationService validationService;
    private final PunishmentEvidenceService punishmentEvidenceService;
    private final StorageMetadataService storageMetadataService;

    public TokenValidationResult validateToken(String token) {
        EvidenceUploadTokenService.UploadToken uploadToken = tokenService.validateToken(token);
        if (uploadToken == null) {
            return TokenValidationResult.invalid();
        }

        Player player = playerRepository.findByMinecraftUuid(uploadToken.serverDatabaseName(), UuidUtil.normalizeUuid(uploadToken.playerUuid()))
            .orElse(null);
        String playerName = player != null ? PlayerDataUtils.extractLatestUsername(player.getUsernames()) : "Unknown";

        return TokenValidationResult.valid(new TokenInfo(
            uploadToken.punishmentId(),
            playerName,
            uploadToken.issuerName()
        ));
    }

    public PresignUploadResult presignUpload(String token, EvidencePresignUploadRequest request) {
        EvidenceUploadTokenService.UploadToken uploadToken = tokenService.validateToken(token);
        if (uploadToken == null) {
            return PresignUploadResult.of(PresignUploadStatus.INVALID_TOKEN, null, null);
        }

        if (!s3StorageService.isConfigured()) {
            return PresignUploadResult.of(PresignUploadStatus.STORAGE_NOT_CONFIGURED, null, null);
        }

        Server server = serverService.getServerByDatabaseName(uploadToken.serverDatabaseName());
        if (server == null) {
            return PresignUploadResult.of(PresignUploadStatus.SERVER_NOT_FOUND, null, null);
        }

        boolean isPremium = server.getPlan() == ServerPlan.PREMIUM;
        MediaValidationService.ValidationResult validation = validationService.validateMetadata(
            request.fileName(),
            request.contentType(),
            request.fileSize(),
            "evidence",
            isPremium
        );
        if (!validation.valid()) {
            return PresignUploadResult.of(PresignUploadStatus.VALIDATION_FAILED, validation.error(), null);
        }

        if (!quotaService.canUpload(server, request.fileSize())) {
            return PresignUploadResult.of(PresignUploadStatus.QUOTA_EXCEEDED,
                "Storage quota exceeded. Please contact the server administrator.",
                null);
        }

        PresignUploadResponse presignedUpload = s3StorageService.createPresignedUploadUrl(
            server,
            "evidence",
            request.fileName(),
            request.contentType(),
            request.fileSize(),
            uploadToken.punishmentId()
        );

        return PresignUploadResult.of(PresignUploadStatus.SUCCESS, null, presignedUpload);
    }

    public ConfirmUploadResult confirmUpload(String token, EvidenceConfirmUploadRequest request) {
        EvidenceUploadTokenService.UploadToken uploadToken = tokenService.validateToken(token);
        if (uploadToken == null) {
            return ConfirmUploadResult.of(ConfirmUploadStatus.INVALID_TOKEN, null);
        }

        String expectedKeyPrefix = uploadToken.serverDatabaseName() + "/evidence/" + uploadToken.punishmentId() + "/";
        if (!request.key().startsWith(expectedKeyPrefix)) {
            return ConfirmUploadResult.of(ConfirmUploadStatus.INVALID_KEY, null);
        }

        UploadResponse uploadDetails = s3StorageService.getUploadDetails(request.key());
        if (uploadDetails == null) {
            return ConfirmUploadResult.of(ConfirmUploadStatus.UPLOAD_NOT_FOUND, null);
        }

        Server server = serverService.getServerByDatabaseName(uploadToken.serverDatabaseName());
        if (server != null) {
            if (!quotaService.confirmAndRecordFile(server, request.key(), uploadDetails.size(), uploadDetails.contentType())) {
                if (!s3StorageService.deleteFile(request.key())) {
                    StorageMetadataService.RecordFileResult recordResult = storageMetadataService.recordReservedFile(server, request.key(), uploadDetails.size(), uploadDetails.contentType());
                    if (recordResult == StorageMetadataService.RecordFileResult.FAILED) {
                        log.error("Orphaned over-quota evidence object: S3 delete failed and metadata write failed key={} serverDb={} size={} contentType={}; next StorageSyncService run (triggered on storage list/aggregate calls) will reconcile, manual cleanup may be needed sooner", request.key(), uploadToken.serverDatabaseName(), uploadDetails.size(), uploadDetails.contentType());
                    } else {
                        log.warn("Failed to delete over-quota evidence object key={}, recorded metadata to keep it trackable result={}", request.key(), recordResult);
                    }
                }
                return ConfirmUploadResult.of(ConfirmUploadStatus.QUOTA_EXCEEDED, null);
            }
        } else {
            log.warn("Could not record storage metadata: server not found for database {}", uploadToken.serverDatabaseName());
        }

        return ConfirmUploadResult.of(ConfirmUploadStatus.SUCCESS, uploadDetails);
    }

    public SubmitEvidenceResult submitEvidence(String token, SubmitEvidenceRequest request) {
        EvidenceUploadTokenService.UploadToken uploadToken = tokenService.validateToken(token);
        if (uploadToken == null) {
            return SubmitEvidenceResult.of(SubmitEvidenceStatus.INVALID_TOKEN, null);
        }

        for (EvidenceItemRequest item : request.evidence()) {
            if (!isAllowedEvidenceUrl(item.url(), uploadToken)) {
                return SubmitEvidenceResult.of(SubmitEvidenceStatus.INVALID_URL, null);
            }
        }

        Server server = serverService.getServerByDatabaseName(uploadToken.serverDatabaseName());
        if (server == null) {
            return SubmitEvidenceResult.of(SubmitEvidenceStatus.SERVER_NOT_FOUND, null);
        }

        List<UploadedEvidenceItem> evidenceItems = request.evidence()
            .stream()
            .map(item -> new UploadedEvidenceItem(
                item.url(),
                item.fileName(),
                item.fileType(),
                item.fileSize()
            ))
            .toList();

        PunishmentOperationResult result = punishmentEvidenceService.addUploadedEvidence(
            server,
            uploadToken.punishmentId(),
            uploadToken.issuerName(),
            null,
            evidenceItems
        );
        if (result.status() == PunishmentOperationStatus.NOT_FOUND) {
            return SubmitEvidenceResult.of(SubmitEvidenceStatus.PUNISHMENT_NOT_FOUND, result.message());
        }

        tokenService.invalidateToken(token);
        return SubmitEvidenceResult.of(SubmitEvidenceStatus.SUCCESS, null);
    }

    private boolean isAllowedEvidenceUrl(String url, EvidenceUploadTokenService.UploadToken uploadToken) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return false;
            }

            String expectedPathFragment = "/" + uploadToken.serverDatabaseName() + "/evidence/" + uploadToken.punishmentId() + "/";
            String path = uri.getPath();
            if (path == null || !path.contains(expectedPathFragment)) {
                return false;
            }

            String cdnDomain = s3StorageService.getCdnDomain();
            return cdnDomain != null && !cdnDomain.isBlank() && cdnDomain.equalsIgnoreCase(uri.getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public enum PresignUploadStatus {
        SUCCESS,
        INVALID_TOKEN,
        STORAGE_NOT_CONFIGURED,
        SERVER_NOT_FOUND,
        VALIDATION_FAILED,
        QUOTA_EXCEEDED
    }

    public enum ConfirmUploadStatus {
        SUCCESS,
        INVALID_TOKEN,
        INVALID_KEY,
        UPLOAD_NOT_FOUND,
        QUOTA_EXCEEDED
    }

    public enum SubmitEvidenceStatus {
        SUCCESS,
        INVALID_TOKEN,
        SERVER_NOT_FOUND,
        INVALID_URL,
        PUNISHMENT_NOT_FOUND
    }

    public record TokenValidationResult(boolean valid, TokenInfo info) {
        private static TokenValidationResult invalid() {
            return new TokenValidationResult(false, null);
        }

        private static TokenValidationResult valid(TokenInfo info) {
            return new TokenValidationResult(true, info);
        }
    }

    public record TokenInfo(String punishmentId, String playerName, String issuerName) {
    }

    public record PresignUploadResult(PresignUploadStatus status, String message, PresignUploadResponse upload) {
        private static PresignUploadResult of(PresignUploadStatus status, String message, PresignUploadResponse upload) {
            return new PresignUploadResult(status, message, upload);
        }
    }

    public record ConfirmUploadResult(ConfirmUploadStatus status, UploadResponse upload) {
        private static ConfirmUploadResult of(ConfirmUploadStatus status, UploadResponse upload) {
            return new ConfirmUploadResult(status, upload);
        }
    }

    public record SubmitEvidenceResult(SubmitEvidenceStatus status, String message) {
        private static SubmitEvidenceResult of(SubmitEvidenceStatus status, String message) {
            return new SubmitEvidenceResult(status, message);
        }

        public HttpStatus httpStatus() {
            return switch (status) {
                case SUCCESS -> HttpStatus.OK;
                case INVALID_TOKEN, SERVER_NOT_FOUND, PUNISHMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case INVALID_URL -> HttpStatus.BAD_REQUEST;
            };
        }
    }

}
