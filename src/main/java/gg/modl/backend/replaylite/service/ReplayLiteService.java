package gg.modl.backend.replaylite.service;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.replaylite.data.ReplayLiteDocument;
import gg.modl.backend.replaylite.data.ReplayLiteLabel;
import gg.modl.backend.replaylite.data.ReplayLiteLabelVerdict;
import gg.modl.backend.replaylite.data.ReplayLiteStatus;
import gg.modl.backend.replaylite.dto.ReplayLitePublicResponse;
import gg.modl.backend.replaylite.dto.ReplayLiteUploadInitRequest;
import gg.modl.backend.replaylite.dto.ReplayLiteUploadInitResponse;
import gg.modl.backend.replaylite.repository.ReplayLiteMongoRepository;
import gg.modl.backend.replaylite.repository.ReplayLiteQuotaMongoRepository;
import gg.modl.backend.replaylite.repository.ReplayLiteQuotaMongoRepository.QuotaReservationResult;
import gg.modl.backend.replaylite.storage.ReplayLiteStorageService;
import gg.modl.backend.server.data.Server;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReplayLiteService {
    public static final long MAX_REPLAY_SIZE_BYTES = 10 * 1024 * 1024L;
    private static final Duration PENDING_UPLOAD_TTL = Duration.ofMinutes(15);
    private static final Duration CONFIRMED_REPLAY_TTL = Duration.ofHours(24);
    private static final int DAILY_CONFIRMED_LIMIT = 100;
    private static final DateTimeFormatter KEY_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final ReplayLiteMongoRepository repository;
    private final ReplayLiteQuotaMongoRepository quotaRepository;
    private final ReplayLiteStorageService storageService;
    private final ReplayLiteAbuseGuard abuseGuard;
    private final Clock clock;

    @Autowired
    public ReplayLiteService(
        ReplayLiteMongoRepository repository,
        ReplayLiteQuotaMongoRepository quotaRepository,
        ReplayLiteStorageService storageService,
        ReplayLiteAbuseGuard abuseGuard
    ) {
        this(repository, quotaRepository, storageService, abuseGuard, Clock.systemUTC());
    }

    public ReplayLiteService(
        ReplayLiteMongoRepository repository,
        ReplayLiteQuotaMongoRepository quotaRepository,
        ReplayLiteStorageService storageService,
        ReplayLiteAbuseGuard abuseGuard,
        Clock clock
    ) {
        this.repository = repository;
        this.quotaRepository = quotaRepository;
        this.storageService = storageService;
        this.abuseGuard = abuseGuard;
        this.clock = clock;
    }

    public ReplayLiteUploadInitResponse initUpload(Server server, ReplayLiteUploadInitRequest request, String clientIp) {
        if (request.requestedSize() > MAX_REPLAY_SIZE_BYTES) {
            throw new ValidationException("Replay Lite uploads cannot exceed 10 MB");
        }

        UUID pluginServerUuid = authenticatedServerUuid(server);
        abuseGuard.checkIp(clientIp);
        abuseGuard.checkInit(pluginServerUuid);
        Instant now = clock.instant();
        long pendingCount = repository.countPendingForServerSince(pluginServerUuid, now.minus(PENDING_UPLOAD_TTL));
        abuseGuard.checkPendingUploads(pendingCount);

        UUID replayUuid = UUID.randomUUID();
        String replayId = replayUuid.toString();
        String objectKey = buildObjectKey(replayUuid, now);

        ReplayLiteDocument document = new ReplayLiteDocument();
        document.setId(replayId);
        document.setPluginServerUuid(pluginServerUuid);
        document.setObjectKey(objectKey);
        document.setStatus(ReplayLiteStatus.PENDING);
        document.setRequestedSize(request.requestedSize());
        document.setMcVersion(request.mcVersion());
        document.setCreatedAt(now);
        document.setUploadInitIp(clientIp);
        repository.saveEntity(document);

        ReplayLiteStorageService.PresignedUpload presignedUpload = storageService.createPresignedUpload(
            objectKey,
            request.requestedSize()
        );

        return new ReplayLiteUploadInitResponse(
            replayId,
            presignedUpload.uploadUrl(),
            presignedUpload.method(),
            presignedUpload.requiredHeaders(),
            presignedUpload.expiresAt()
        );
    }

    public void confirmUpload(Server server, String replayId, String clientIp) {
        abuseGuard.checkIp(clientIp);
        UUID pluginServerUuid = authenticatedServerUuid(server);

        ReplayLiteDocument document = repository.findByReplayId(replayId)
            .orElseThrow(() -> new ResourceNotFoundException("Replay not found"));

        if (!pluginServerUuid.equals(document.getPluginServerUuid())) {
            throw new ResourceNotFoundException("Replay not found");
        }

        abuseGuard.checkConfirm(pluginServerUuid);
        requirePending(document);
        requireNotStale(document);

        ReplayLiteStorageService.ObjectMetadata metadata = storageService.headObject(document.getObjectKey())
            .orElseThrow(() -> new ResourceNotFoundException("Replay upload object was not found"));

        long actualSize = metadata.size();
        if (actualSize > MAX_REPLAY_SIZE_BYTES) {
            throw new ValidationException("Replay Lite upload exceeds 10 MB");
        }
        if (actualSize > document.getRequestedSize()) {
            throw new ValidationException("Replay Lite upload exceeds requested size");
        }

        LocalDate quotaDay = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        QuotaReservationResult quotaReservation = reserveDailyQuota(document.getPluginServerUuid(), document.getId(), quotaDay);
        if (quotaReservation == QuotaReservationResult.ALREADY_RESERVED) {
            throw new ConflictException("Replay Lite upload is not pending");
        }

        boolean confirmed;
        try {
            confirmed = confirmPendingUpload(document, actualSize, clientIp);
        } catch (RuntimeException e) {
            if (quotaReservation == QuotaReservationResult.RESERVED && shouldReleaseFailedConfirmation(document.getId())) {
                quotaRepository.releaseConfirmedUpload(
                    document.getPluginServerUuid(),
                    quotaDay,
                    document.getId(),
                    clock.instant()
                );
            }
            throw e;
        }
        if (!confirmed) {
            if (quotaReservation == QuotaReservationResult.RESERVED && shouldReleaseFailedConfirmation(document.getId())) {
                quotaRepository.releaseConfirmedUpload(
                    document.getPluginServerUuid(),
                    quotaDay,
                    document.getId(),
                    clock.instant()
                );
            }
            throw new ConflictException("Replay Lite upload is not pending");
        }
    }

    public Optional<ReplayLitePublicResponse> getPublicReplay(String replayId) {
        return findAvailablePublicReplay(replayId)
            .map(document -> new ReplayLitePublicResponse(
                document.getId(),
                document.getMcVersion(),
                document.getConfirmedSize() == null ? 0 : document.getConfirmedSize(),
                document.getConfirmedAt() == null ? document.getCreatedAt().toEpochMilli() : document.getConfirmedAt().toEpochMilli(),
                RESTMappingV1.PUBLIC_REPLAY_LITE_REPLAYS + "/" + document.getId() + "/download",
                document.getStatus().name(),
                document.getLabels() != null && !document.getLabels().isEmpty()
            ));
    }

    public Optional<ReplayLiteDownload> getPublicReplayDownload(String replayId) {
        return findAvailablePublicReplay(replayId)
            .flatMap(document -> storageService.downloadObject(document.getObjectKey(), MAX_REPLAY_SIZE_BYTES)
                .map(download -> new ReplayLiteDownload(
                    download.bytes(),
                    download.contentType(),
                    document.getExpiresAt()
                )));
    }

    public void submitLabels(String replayId, List<ReplayLiteLabel> labels, String clientIp) {
        abuseGuard.checkIp(clientIp);
        abuseGuard.checkLabel(replayId);

        ReplayLiteDocument document = repository.findByReplayId(replayId)
            .orElseThrow(() -> new ResourceNotFoundException("Replay not found"));

        Instant now = clock.instant();
        if (document.getStatus() != ReplayLiteStatus.CONFIRMED || document.getExpiresAt() == null || !document.getExpiresAt().isAfter(now)) {
            throw new ResourceNotFoundException("Replay not found");
        }
        if (document.getLabels() != null && !document.getLabels().isEmpty()) {
            throw new ConflictException("This replay has already been labeled");
        }

        if (!repository.claimLabels(document.getId(), now, normalizeLabels(labels), clientIp)) {
            Optional<ReplayLiteDocument> current = repository.findByReplayId(replayId);
            if (current.isEmpty()
                || current.get().getStatus() != ReplayLiteStatus.CONFIRMED
                || current.get().getExpiresAt() == null
                || !current.get().getExpiresAt().isAfter(now)) {
                throw new ResourceNotFoundException("Replay not found");
            }
            throw new ConflictException("This replay has already been labeled");
        }
    }

    private Optional<ReplayLiteDocument> findAvailablePublicReplay(String replayId) {
        Instant now = clock.instant();
        return repository.findByReplayId(replayId)
            .filter(document -> document.getStatus() == ReplayLiteStatus.CONFIRMED)
            .filter(document -> document.getExpiresAt() != null && document.getExpiresAt().isAfter(now));
    }

    private List<ReplayLiteLabel> normalizeLabels(List<ReplayLiteLabel> labels) {
        return labels.stream()
            .map(label -> new ReplayLiteLabel(
                label.playerName(),
                ReplayLiteLabelVerdict.from(label.verdict())
                    .orElseThrow(() -> new ValidationException("Replay Lite label verdict is invalid"))
                    .name(),
                label.ranges(),
                label.notes()
            ))
            .toList();
    }

    private String buildObjectKey(UUID replayUuid, Instant now) {
        String date = KEY_DATE_FORMATTER.format(LocalDate.ofInstant(now, ZoneOffset.UTC));
        return "replay-lite/" + date + "/" + replayUuid + ".modlreplay";
    }

    private UUID authenticatedServerUuid(Server server) {
        String serverId = server.getId();
        if (serverId == null || serverId.isBlank()) {
            throw new ValidationException("Authenticated server is missing an id");
        }
        try {
            return UUID.fromString(serverId);
        } catch (IllegalArgumentException ignored) {
            return UUID.nameUUIDFromBytes(serverId.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void requirePending(ReplayLiteDocument document) {
        if (document.getStatus() != ReplayLiteStatus.PENDING) {
            throw new ValidationException("Replay Lite upload is not pending");
        }
    }

    private void requireNotStale(ReplayLiteDocument document) {
        Instant staleAt = document.getCreatedAt().plus(PENDING_UPLOAD_TTL);
        if (!clock.instant().isBefore(staleAt)) {
            throw new ValidationException("Replay Lite upload has expired");
        }
    }

    private QuotaReservationResult reserveDailyQuota(UUID pluginServerUuid, String replayId, LocalDate utcDate) {
        QuotaReservationResult result = quotaRepository.reserveConfirmedUpload(
            pluginServerUuid,
            utcDate,
            replayId,
            DAILY_CONFIRMED_LIMIT,
            clock.instant()
        );
        if (result == QuotaReservationResult.LIMIT_REACHED) {
            throw new ValidationException("Replay Lite daily upload limit reached");
        }
        return result;
    }

    private boolean confirmPendingUpload(ReplayLiteDocument document, long actualSize, String clientIp) {
        Instant confirmedAt = clock.instant();
        Instant expiresAt = confirmedAt.plus(CONFIRMED_REPLAY_TTL);
        Instant freshCreatedAfter = confirmedAt.minus(PENDING_UPLOAD_TTL);
        return repository.confirmPendingUpload(
            document.getId(),
            actualSize,
            confirmedAt,
            expiresAt,
            clientIp,
            freshCreatedAfter
        );
    }

    private boolean shouldReleaseFailedConfirmation(String replayId) {
        return repository.findByReplayId(replayId)
            .map(document -> document.getStatus() != ReplayLiteStatus.CONFIRMED)
            .orElse(true);
    }

    public record ReplayLiteDownload(byte[] bytes, String contentType, Instant expiresAt) {}
}
