package gg.modl.backend.billing.service;

import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.data.SubscriptionStatus;
import gg.modl.backend.server.service.ServerMutationHelper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionExpiryService {
    private final ServerMongoRepository serverRepository;
    private final UsageTrackingService usageTrackingService;
    private final ServerMutationHelper serverMutationHelper;

    @Scheduled(fixedRate = 3600000)
    public void checkExpiredSubscriptions() {
        try {
            List<Server> cancelledServers = serverRepository.findCancelledWithPeriodEnd();
            Date now = new Date();

            for (Server server : cancelledServers) {
                Date endDate = server.getCurrentPeriodEnd();
                if (endDate != null && endDate.before(now)) {
                    serverMutationHelper.mutate(server, current -> {
                        current.setSubscriptionStatus(SubscriptionStatus.INACTIVE);
                        current.setPlan(ServerPlan.FREE);
                        current.setCurrentPeriodEnd(null);
                    });
                    usageTrackingService.resetUsageCounters(server.getId());
                }
            }
        } catch (Exception exception) {
            log.error("Error checking for expired subscriptions", exception);
        }
    }
}
