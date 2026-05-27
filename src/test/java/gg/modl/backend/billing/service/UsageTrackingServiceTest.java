package gg.modl.backend.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.service.ServerMutationHelper;
import java.util.Date;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UsageTrackingServiceTest {

    @Mock
    private ServerMongoRepository serverRepository;

    @Mock
    private ServerMutationHelper serverMutationHelper;

    private UsageTrackingService usageTrackingService;

    @BeforeEach
    void setUp() {
        usageTrackingService = new UsageTrackingService(serverRepository, serverMutationHelper);
    }

    @Test
    void updateUsageBillingSettingsPersistsFlagsThroughRepository() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setId("server-1");
        server.setStripeCustomerId("cus_123");

        doAnswer(invocation -> {
            Consumer<Server> mutator = invocation.getArgument(1);
            mutator.accept(invocation.getArgument(0));
            return null;
        }).when(serverMutationHelper).mutate(any(Server.class), any());

        usageTrackingService.updateUsageBillingSettings(server, true);

        verify(serverMutationHelper).mutate(any(Server.class), any());
        assertTrue(Boolean.TRUE.equals(server.getUsageBillingEnabled()));
        assertTrue(server.getUsageBillingUpdatedAt() != null);
    }

    @Test
    void incrementCdnUsageUsesTypedAtomicUpdate() {
        usageTrackingService.incrementCdnUsage("server-1", 1.5);

        verify(serverRepository).incrementCdnUsage("server-1", 1.5);
    }

    @Test
    void getCdnLimitGbUsesCustomPremiumStorageLimitWhenConfigured() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setMaxStorageLimitBytes(512L * 1024 * 1024 * 1024);

        assertEquals(512.0, usageTrackingService.getCdnLimitGB(server));
    }

    @Test
    void getCdnLimitGbFallsBackForPremiumServerWithoutCustomLimit() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);

        assertEquals(200.0, usageTrackingService.getCdnLimitGB(server));
    }

    @Test
    void applyBillingPeriodRolloverFirstTimeResetsCountersAndStampsResetDate() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setId("server-1");
        server.setAiRequestsCurrentPeriod(750L);
        server.setCdnUsageCurrentPeriod(42.0);
        server.setUsageResetAt(null);

        doAnswer(invocation -> {
            Consumer<Server> mutator = invocation.getArgument(1);
            mutator.accept(invocation.getArgument(0));
            return null;
        }).when(serverMutationHelper).mutate(any(Server.class), any());

        Date newPeriodStart = new Date(2_000_000_000_000L);
        Date newPeriodEnd = new Date(2_000_000_000_000L + 30L * 24 * 60 * 60 * 1000);

        boolean rolled = usageTrackingService.applyBillingPeriodRollover(server, newPeriodStart, newPeriodEnd);

        assertTrue(rolled);
        assertEquals(0L, server.getAiRequestsCurrentPeriod());
        assertEquals(0.0, server.getCdnUsageCurrentPeriod());
        assertEquals(newPeriodStart, server.getUsageResetAt());
        assertEquals(newPeriodStart, server.getCurrentPeriodStart());
        assertEquals(newPeriodEnd, server.getCurrentPeriodEnd());
        verify(serverMutationHelper).mutate(any(Server.class), any());
    }

    @Test
    void applyBillingPeriodRolloverResetsWhenPeriodStartAdvances() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setId("server-1");
        Date oldPeriodStart = new Date(1_000_000_000_000L);
        server.setUsageResetAt(oldPeriodStart);
        server.setCurrentPeriodStart(oldPeriodStart);
        server.setAiRequestsCurrentPeriod(950L);
        server.setCdnUsageCurrentPeriod(180.0);

        doAnswer(invocation -> {
            Consumer<Server> mutator = invocation.getArgument(1);
            mutator.accept(invocation.getArgument(0));
            return null;
        }).when(serverMutationHelper).mutate(any(Server.class), any());

        Date newPeriodStart = new Date(oldPeriodStart.getTime() + 30L * 24 * 60 * 60 * 1000);
        Date newPeriodEnd = new Date(newPeriodStart.getTime() + 30L * 24 * 60 * 60 * 1000);

        boolean rolled = usageTrackingService.applyBillingPeriodRollover(server, newPeriodStart, newPeriodEnd);

        assertTrue(rolled);
        assertEquals(0L, server.getAiRequestsCurrentPeriod());
        assertEquals(0.0, server.getCdnUsageCurrentPeriod());
        assertEquals(newPeriodStart, server.getUsageResetAt());
    }

    @Test
    void applyBillingPeriodRolloverIsIdempotentWithinSamePeriod() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setId("server-1");
        Date periodStart = new Date(1_000_000_000_000L);
        server.setUsageResetAt(periodStart);
        server.setCurrentPeriodStart(periodStart);
        server.setAiRequestsCurrentPeriod(123L);
        server.setCdnUsageCurrentPeriod(5.5);

        boolean rolled = usageTrackingService.applyBillingPeriodRollover(server, periodStart, new Date(periodStart.getTime() + 1000));

        assertFalse(rolled);
        assertEquals(123L, server.getAiRequestsCurrentPeriod());
        assertEquals(5.5, server.getCdnUsageCurrentPeriod());
        verify(serverMutationHelper, never()).mutate(any(Server.class), any());
    }

    @Test
    void applyBillingPeriodRolloverIgnoresNullPeriodStart() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setId("server-1");
        server.setAiRequestsCurrentPeriod(10L);

        boolean rolled = usageTrackingService.applyBillingPeriodRollover(server, null, null);

        assertFalse(rolled);
        assertEquals(10L, server.getAiRequestsCurrentPeriod());
        assertNull(server.getUsageResetAt());
        verify(serverMutationHelper, never()).mutate(any(Server.class), any());
    }
}
