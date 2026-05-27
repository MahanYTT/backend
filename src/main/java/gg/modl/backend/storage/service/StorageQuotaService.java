package gg.modl.backend.storage.service;

import gg.modl.backend.billing.service.UsageTrackingService;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.util.ByteFormatUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.storage.dto.response.StorageQuotaResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageQuotaService {
    private final StorageMetadataService storageMetadataService;
    private final S3StorageService s3StorageService;
    private final UsageTrackingService usageTrackingService;
    private final ServerMongoRepository serverRepository;
    public static final long MAX_PREMIUM_BYTES = 2200L * 1024L * 1024 * 1024; // 2200 GB (200 base + 2000 max overage)
    private static final long FREE_TIER_BYTES = 1024L * 1024 * 1024; // 1 GB
    private static final long DEFAULT_PREMIUM_BYTES = 200L * 1024 * 1024 * 1024; // 200 GB
    private static final double AI_OVERAGE_RATE = 0.02;

    public boolean canUpload(Server server, long fileSize) {
        long used = s3StorageService.calculateStorageUsed(server);
        long max = getMaxBytesForServer(server);
        return used + fileSize <= max;
    }

    public boolean isWithinQuota(Server server) {
        long used = s3StorageService.calculateStorageUsed(server);
        long max = getMaxBytesForServer(server);
        return used <= max;
    }

    public boolean confirmAndRecordFile(Server server, String key, long size, String contentType) {
        if (size < 0) {
            return false;
        }
        if (storageMetadataService.hasFile(server, key)) {
            return true;
        }

        long maxBytes = getMaxBytesForServer(server);
        reconcileTrackedStorageUsage(server);
        if (!serverRepository.tryIncrementStorageUsedWithinLimit(server.getId(), size, maxBytes)) {
            return false;
        }

        StorageMetadataService.RecordFileResult recordResult =
            storageMetadataService.recordReservedFile(server, key, size, contentType);
        if (recordResult == StorageMetadataService.RecordFileResult.INSERTED) {
            if (server.getStorageUsedBytes() != null) {
                server.setStorageUsedBytes(server.getStorageUsedBytes() + size);
            }
            return true;
        }

        serverRepository.decrementStorageUsed(server.getId(), size);
        return recordResult == StorageMetadataService.RecordFileResult.ALREADY_EXISTS;
    }

    public StorageQuotaResponse getQuota(Server server) {
        Map<String, Long> byType = storageMetadataService.calculateStorageByType(server);
        long usedBytes = byType.values()
            .stream().mapToLong(Long::longValue).sum();
        long maxBytes = getMaxBytesForServer(server);

        double usedPercentage = maxBytes > 0 ? (double) usedBytes / maxBytes * 100 : 0;

        boolean isPremium = server.getPlan() == ServerPlan.PREMIUM;
        StorageQuotaResponse.AiQuotaInfo aiQuota = buildAiQuotaInfo(server, isPremium);

        return new StorageQuotaResponse(
            usedBytes,
            maxBytes,
            Math.round(usedPercentage * 100) / 100.0,
            ByteFormatUtil.format(usedBytes),
            ByteFormatUtil.format(maxBytes),
            byType,
            aiQuota,
            isPremium,
            0.08
        );
    }

    private StorageQuotaResponse.AiQuotaInfo buildAiQuotaInfo(Server server, boolean isPremium) {
        long totalUsed = server.getAiRequestsCurrentPeriod() != null ? server.getAiRequestsCurrentPeriod() : 0L;
        long includedLimit = usageTrackingService.getAiBaseLimitRequests();
        long requestLimit = usageTrackingService.getAiRequestLimit(server);
        boolean usageBillingEnabled = Boolean.TRUE.equals(server.getUsageBillingEnabled());
        long overageUsed = usageBillingEnabled ? Math.max(0, totalUsed - includedLimit) : 0L;
        double overageCost = usageBillingEnabled ? overageUsed * AI_OVERAGE_RATE : 0.0;
        double usagePercentage = requestLimit > 0 ? (double) totalUsed / requestLimit * 100 : 0;

        return new StorageQuotaResponse.AiQuotaInfo(
            totalUsed,
            requestLimit,
            overageUsed,
            overageCost,
            isPremium && totalUsed < requestLimit,
            Math.round(usagePercentage * 100) / 100.0,
            Map.of("other", totalUsed)
        );
    }

    private long getMaxBytesForServer(Server server) {
        if (server.getPlan() == ServerPlan.PREMIUM) {
            if (server.getMaxStorageLimitBytes() != null && server.getMaxStorageLimitBytes() > 0) {
                return server.getMaxStorageLimitBytes();
            }
            return DEFAULT_PREMIUM_BYTES;
        }
        return FREE_TIER_BYTES;
    }

    private void reconcileTrackedStorageUsage(Server server) {
        long liveUsed = s3StorageService.calculateStorageUsed(server);
        if (server.getStorageUsedBytes() != null && server.getStorageUsedBytes() >= liveUsed) {
            return;
        }
        serverRepository.setStorageUsed(server.getId(), liveUsed);
        server.setStorageUsedBytes(liveUsed);
    }
}
