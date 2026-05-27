package gg.modl.backend.billing.service;

import gg.modl.backend.billing.dto.response.UsageBillingSettingsResponse;
import gg.modl.backend.billing.dto.response.UsageResponse;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.service.ServerMutationHelper;
import gg.modl.backend.storage.service.StorageQuotaService;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsageTrackingService {
    private final ServerMongoRepository serverRepository;
    private final ServerMutationHelper serverMutationHelper;
    private static final double FREE_CDN_LIMIT_GB = 1.0;
    private static final double DEFAULT_PREMIUM_CDN_LIMIT_GB = 200.0;
    private static final long AI_BASE_LIMIT_REQUESTS = 1000L;
    private static final double CDN_OVERAGE_RATE = 0.08;
    private static final double AI_OVERAGE_RATE = 0.02;

    public UsageResponse getUsage(Server server) {
        Server freshServer = getFreshServer(server.getId());
        if (freshServer == null) {
            throw new IllegalStateException("Server not found in database.");
        }

        Date currentPeriodStart = freshServer.getCurrentPeriodStart();
        if (currentPeriodStart == null) {
            currentPeriodStart = new Date(System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000));
        }

        Date currentPeriodEnd = freshServer.getCurrentPeriodEnd();
        if (currentPeriodEnd == null) {
            currentPeriodEnd = new Date(System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000));
        }

        double cdnUsageGB = freshServer.getCdnUsageCurrentPeriod() != null ? freshServer.getCdnUsageCurrentPeriod() : 0.0;
        long aiRequestsUsed = freshServer.getAiRequestsCurrentPeriod() != null ? freshServer.getAiRequestsCurrentPeriod() : 0L;
        boolean usageBillingEnabled = Boolean.TRUE.equals(freshServer.getUsageBillingEnabled());

        double cdnLimitGB = getCdnLimitGB(freshServer);
        double cdnOverageGB = Math.max(0, cdnUsageGB - cdnLimitGB);
        long aiLimitRequests = getAiRequestLimit(freshServer);
        long aiOverageRequests = usageBillingEnabled
                                 ? Math.max(0, aiRequestsUsed - getAiBaseLimitRequests())
                                 : 0L;

        double cdnOverageCost = usageBillingEnabled ? cdnOverageGB * CDN_OVERAGE_RATE : 0.0;
        double aiOverageCost = usageBillingEnabled ? aiOverageRequests * AI_OVERAGE_RATE : 0.0;
        double totalOverageCost = cdnOverageCost + aiOverageCost;

        return new UsageResponse(
            new UsageResponse.Period(currentPeriodStart, currentPeriodEnd),
            new UsageResponse.UsageMetric(
                cdnUsageGB,
                cdnLimitGB,
                cdnOverageGB,
                CDN_OVERAGE_RATE,
                cdnOverageCost,
                Math.min(100, cdnLimitGB > 0 ? (cdnUsageGB / cdnLimitGB) * 100 : 0)
            ),
            new UsageResponse.UsageMetric(
                aiRequestsUsed,
                aiLimitRequests,
                aiOverageRequests,
                AI_OVERAGE_RATE,
                aiOverageCost,
                Math.min(100, aiLimitRequests > 0 ? ((double) aiRequestsUsed / aiLimitRequests) * 100 : 0)
            ),
            totalOverageCost,
            usageBillingEnabled
        );
    }

    public double getCdnLimitGB(Server server) {
        if (server.getPlan() == ServerPlan.PREMIUM) {
            if (server.getMaxStorageLimitBytes() != null && server.getMaxStorageLimitBytes() > 0) {
                return server.getMaxStorageLimitBytes() / (1024.0 * 1024 * 1024);
            }
            return DEFAULT_PREMIUM_CDN_LIMIT_GB;
        }
        return FREE_CDN_LIMIT_GB;
    }

    public long getAiRequestLimit(Server server) {
        long overageCap = server.getMaxAiOverageRequests() != null
                          ? Math.max(0, server.getMaxAiOverageRequests())
                          : 0L;
        return AI_BASE_LIMIT_REQUESTS + overageCap;
    }

    public long getAiBaseLimitRequests() {
        return AI_BASE_LIMIT_REQUESTS;
    }

    private Server getFreshServer(String serverId) {
        return serverRepository.findById(serverId).orElse(null);
    }

    public UsageBillingSettingsResponse updateUsageBillingSettings(Server server, boolean enabled) {
        if (enabled && (server.getStripeCustomerId() == null || server.getStripeCustomerId().isBlank())) {
            throw new IllegalStateException("No Stripe customer ID found. Please ensure you have an active subscription.");
        }

        serverMutationHelper.mutate(server, current -> {
            current.setUsageBillingEnabled(enabled);
            current.setUsageBillingUpdatedAt(new Date());
        });

        String message = enabled
                         ? "Usage billing has been enabled. You will be charged for overages at the end of each billing period."
                         : "Usage billing has been disabled. Overages will not be charged.";

        return new UsageBillingSettingsResponse(true, message, enabled);
    }

    public void incrementCdnUsage(String serverId, double additionalGB) {
        serverRepository.incrementCdnUsage(serverId, additionalGB);
    }

    public void incrementAiRequests(String serverId, long additionalRequests) {
        serverRepository.incrementAiRequests(serverId, additionalRequests);
    }

    public void resetUsageCounters(String serverId) {
        serverRepository.resetUsageCounters(serverId);
    }

    public boolean applyBillingPeriodRollover(Server server, Date newPeriodStart, Date newPeriodEnd) {
        if (newPeriodStart == null) {
            return false;
        }
        Date lastReset = server.getUsageResetAt();
        if (lastReset != null && !lastReset.before(newPeriodStart)) {
            return false;
        }

        serverMutationHelper.mutate(server, current -> {
            current.setUsageResetAt(newPeriodStart);
            current.setCurrentPeriodStart(newPeriodStart);
            if (newPeriodEnd != null) {
                current.setCurrentPeriodEnd(newPeriodEnd);
            }
            current.setAiRequestsCurrentPeriod(0L);
            current.setCdnUsageCurrentPeriod(0.0);
        });
        log.info("Reset usage counters for server {} on billing period rollover to {}", server.getId(), newPeriodStart);
        return true;
    }

    public void updateStorageLimit(Server server, long bytes) {
        if (server.getPlan() != ServerPlan.PREMIUM) {
            throw new ValidationException("Storage limit configuration is only available for premium servers");
        }
        if (bytes > StorageQuotaService.MAX_PREMIUM_BYTES) {
            throw new ValidationException("Storage limit cannot exceed 2200 GB. Please contact support for higher limits.");
        }
        serverMutationHelper.mutate(server, current -> current.setMaxStorageLimitBytes(bytes));
    }

    public void updateOverageLimits(Server server, long maxStorageLimitBytes, long maxAiOverageRequests) {
        if (server.getPlan() != ServerPlan.PREMIUM) {
            throw new ValidationException("Overage limits configuration is only available for premium servers");
        }
        serverMutationHelper.mutate(server, current -> {
            current.setMaxStorageLimitBytes(maxStorageLimitBytes);
            current.setMaxAiOverageRequests(maxAiOverageRequests);
        });
    }
}
