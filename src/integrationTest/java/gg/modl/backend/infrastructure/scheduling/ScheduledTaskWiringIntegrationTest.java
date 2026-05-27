package gg.modl.backend.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.analytics.service.MetricSnapshotService;
import gg.modl.backend.billing.service.SubscriptionExpiryService;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.schedule.RealtimeHeartbeatSweeper;
import gg.modl.backend.registration.cleanup.RegistrationCleanupService;
import gg.modl.backend.registration.config.RegistrationCleanupProperties;
import gg.modl.backend.replay.config.LegacyReplayCleanupProperties;
import gg.modl.backend.replay.service.LegacyReplayCleanupService;
import gg.modl.backend.replaylite.service.ReplayLiteCleanupService;
import gg.modl.backend.storage.service.EvidenceUploadTokenService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.Task;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies that every {@code @Scheduled} cleanup worker is actually registered with Spring's
 * scheduling subsystem and uses the expected trigger. Unit tests exercise each worker's body
 * in isolation but nothing previously confirmed the wiring — a typo or missing
 * {@code @EnableScheduling} would have silently disabled production cleanup.
 *
 * <p>All data-layer collaborators are mocked because we only inspect registration metadata; we
 * never let the scheduler actually run the worker bodies (initial-fire on {@code fixedRate} would
 * hit the mocks but is harmless because their defaults are no-op safe).
 */
@SpringBootTest(classes = ScheduledTaskWiringIntegrationTest.SchedulingTestConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    // Make placeholder-driven triggers deterministic regardless of the application-test profile.
    "modl.replay-lite.cleanup-delay-ms=300000",
    "modl.replay.cleanup.interval-ms=3600000",
    "modl.registration.cleanup.interval-ms=3600000",
    "modl.realtime.ws.heartbeat-sweep-interval-ms=15000"
})
class ScheduledTaskWiringIntegrationTest {

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledProcessor;

    // Repositories and services the workers depend on. Mockito defaults (null for objects,
    // empty collections for List/Set returns) are safe for registration-only assertions.
    @MockitoBean
    private gg.modl.backend.database.mongo.repository.ServerMongoRepository serverRepository;
    @MockitoBean
    private gg.modl.backend.database.mongo.repository.MetricSnapshotMongoRepository metricSnapshotRepository;
    @MockitoBean
    private gg.modl.backend.database.mongo.repository.ServerInstanceSnapshotMongoRepository serverInstanceSnapshotRepository;
    @MockitoBean
    private gg.modl.backend.database.mongo.repository.ReplayMongoRepository replayRepository;
    @MockitoBean
    private gg.modl.backend.database.mongo.repository.ServerDatabaseMongoRepository serverDatabaseRepository;
    @MockitoBean
    private gg.modl.backend.replaylite.repository.ReplayLiteMongoRepository replayLiteRepository;
    @MockitoBean
    private gg.modl.backend.replaylite.storage.ReplayLiteStorageService replayLiteStorageService;
    @MockitoBean
    private gg.modl.backend.billing.service.UsageTrackingService usageTrackingService;
    @MockitoBean
    private gg.modl.backend.server.service.ServerMutationHelper serverMutationHelper;
    @MockitoBean
    private gg.modl.backend.server.ServerService serverService;
    @MockitoBean
    private gg.modl.backend.role.service.PermissionService permissionService;
    @MockitoBean
    private gg.modl.backend.staff.service.StaffService staffService;
    @MockitoBean
    private gg.modl.backend.settings.service.ReplayRetentionSettingsService replayRetentionSettingsService;
    @MockitoBean
    private gg.modl.backend.storage.service.S3StorageService s3StorageService;
    @MockitoBean
    private gg.modl.backend.storage.service.StorageMetadataService storageMetadataService;
    @MockitoBean
    private gg.modl.backend.realtime.state.RealtimeConnectionRegistry realtimeConnectionRegistry;
    @MockitoBean
    private gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup realtimeConnectionCleanup;
    @MockitoBean
    private gg.modl.backend.realtime.metrics.RealtimeMetrics realtimeMetrics;
    @MockitoBean
    private gg.modl.backend.realtime.transport.RealtimeSessionOperations realtimeSessionOperations;

    @Test
    void evidenceUploadTokenServiceFiresEveryFiveMinutes() {
        FixedRateTask task = findFixedRateTask(EvidenceUploadTokenService.class, "cleanupExpiredTokens");
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void subscriptionExpiryServiceFiresEveryHour() {
        FixedRateTask task = findFixedRateTask(SubscriptionExpiryService.class, "checkExpiredSubscriptions");
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void metricSnapshotServiceTakesSnapshotsEveryFiveMinutes() {
        CronTask task = findCronTask(MetricSnapshotService.class, "takeSnapshot");
        assertThat(task.getExpression()).isEqualTo("0 */5 * * * *");
    }

    @Test
    void metricSnapshotServicePurgesOldSnapshotsDailyAtThreeAm() {
        CronTask task = findCronTask(MetricSnapshotService.class, "purgeOldSnapshots");
        assertThat(task.getExpression()).isEqualTo("0 0 3 * * *");
    }

    @Test
    void replayLiteCleanupServiceFiresWithConfiguredDelay() {
        FixedDelayTask task = findFixedDelayTask(ReplayLiteCleanupService.class, "cleanupExpiredReplays");
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void legacyReplayCleanupServiceFiresWithConfiguredDelay() {
        FixedDelayTask task = findFixedDelayTask(LegacyReplayCleanupService.class, "runScheduledCleanup");
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void registrationCleanupServiceFiresWithConfiguredDelay() {
        FixedDelayTask task = findFixedDelayTask(RegistrationCleanupService.class, "runScheduledCleanup");
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void realtimeHeartbeatSweeperFiresEveryFifteenSeconds() {
        FixedDelayTask task = findFixedDelayTask(RealtimeHeartbeatSweeper.class, "closeTimedOutSessions");
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void everyAnnotatedMethodIsRegistered() {
        Set<ScheduledTask> registered = scheduledProcessor.getScheduledTasks();
        assertThat(registered).hasSize(8);
    }

    private FixedRateTask findFixedRateTask(Class<?> targetClass, String methodName) {
        Task task = findTask(targetClass, methodName);
        assertThat(task)
            .as("Expected %s.%s to be a fixed-rate task", targetClass.getSimpleName(), methodName)
            .isInstanceOf(FixedRateTask.class);
        return (FixedRateTask) task;
    }

    private FixedDelayTask findFixedDelayTask(Class<?> targetClass, String methodName) {
        Task task = findTask(targetClass, methodName);
        assertThat(task)
            .as("Expected %s.%s to be a fixed-delay task", targetClass.getSimpleName(), methodName)
            .isInstanceOf(FixedDelayTask.class);
        return (FixedDelayTask) task;
    }

    private CronTask findCronTask(Class<?> targetClass, String methodName) {
        Task task = findTask(targetClass, methodName);
        assertThat(task)
            .as("Expected %s.%s to be a cron task", targetClass.getSimpleName(), methodName)
            .isInstanceOf(CronTask.class);
        return (CronTask) task;
    }

    private Task findTask(Class<?> targetClass, String methodName) {
        Set<ScheduledTask> tasks = scheduledProcessor.getScheduledTasks();
        Optional<ScheduledTask> match = tasks.stream()
            .filter(scheduledTask -> matchesMethod(scheduledTask, targetClass, methodName))
            .findFirst();
        return match
            .orElseThrow(() -> new AssertionError(
                "No scheduled task registered for " + targetClass.getName() + "." + methodName
                    + ". Registered: " + tasks.stream()
                        .map(t -> describe(t.getTask().getRunnable()))
                        .toList()))
            .getTask();
    }

    private boolean matchesMethod(ScheduledTask scheduledTask, Class<?> targetClass, String methodName) {
        ScheduledMethodRunnable smr = unwrapScheduledMethodRunnable(scheduledTask.getTask().getRunnable());
        if (smr == null) {
            return false;
        }
        Method method = smr.getMethod();
        if (!method.getName().equals(methodName)) {
            return false;
        }
        // Spring may proxy the bean (CGLIB); use AopUtils to recover the original class so
        // declaring-class equality holds across proxy boundaries.
        Class<?> ultimate = AopUtils.getTargetClass(smr.getTarget());
        return targetClass.isAssignableFrom(ultimate);
    }

    /**
     * In Spring 6+ a {@link ScheduledMethodRunnable} is wrapped in
     * {@code Task$OutcomeTrackingRunnable} (package-private), which holds the original runnable
     * in a private {@code runnable} field. Walk wrappers reflectively rather than rely on
     * {@code toString()}.
     */
    private ScheduledMethodRunnable unwrapScheduledMethodRunnable(Runnable runnable) {
        Runnable current = runnable;
        for (int i = 0; current != null && i < 5; i++) {
            if (current instanceof ScheduledMethodRunnable methodRunnable) {
                return methodRunnable;
            }
            Runnable inner = readRunnableField(current);
            if (inner == null || inner == current) {
                return null;
            }
            current = inner;
        }
        return null;
    }

    private Runnable readRunnableField(Runnable wrapper) {
        for (Field field : wrapper.getClass().getDeclaredFields()) {
            if (Runnable.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(wrapper);
                    if (value instanceof Runnable r) {
                        return r;
                    }
                } catch (IllegalAccessException ignored) {
                    // try the next field
                }
            }
        }
        return null;
    }

    private String describe(Runnable runnable) {
        ScheduledMethodRunnable smr = unwrapScheduledMethodRunnable(runnable);
        if (smr == null) {
            return runnable.getClass().getName();
        }
        return AopUtils.getTargetClass(smr.getTarget()).getName() + "." + smr.getMethod().getName();
    }

    @SpringBootConfiguration
    @EnableScheduling
    @EnableConfigurationProperties({
        RegistrationCleanupProperties.class,
        LegacyReplayCleanupProperties.class,
        RealtimeProperties.class
    })
    @Import({
        EvidenceUploadTokenService.class,
        SubscriptionExpiryService.class,
        MetricSnapshotService.class,
        ReplayLiteCleanupService.class,
        LegacyReplayCleanupService.class,
        RegistrationCleanupService.class,
        RealtimeHeartbeatSweeper.class
    })
    static class SchedulingTestConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
