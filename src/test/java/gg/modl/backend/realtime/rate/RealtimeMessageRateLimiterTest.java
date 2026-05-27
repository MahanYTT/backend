package gg.modl.backend.realtime.rate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RealtimeMessageRateLimiterTest {

    @Test
    void springCanCreateRateLimiterBeanWithRealtimePropertiesDependency() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RealtimeProperties.class);
            context.registerBean(
                RealtimeMessageRateLimiter.class,
                () -> new RealtimeMessageRateLimiter(context.getBean(RealtimeProperties.class))
            );

            assertDoesNotThrow(context::refresh);

            assertNotNull(context.getBean(RealtimeMessageRateLimiter.class));
        }
    }

    @Test
    void rejectsMessagesAfterConnectionExceedsWindowLimit() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setInboundRateLimitMessages(2);
        properties.setInboundRateLimitWindowSeconds(60);
        RealtimeMessageRateLimiter limiter = new RealtimeMessageRateLimiter(properties);
        RealtimeConnectionState state = new RealtimeConnectionState();

        assertTrue(limiter.tryAcquire(state));
        assertTrue(limiter.tryAcquire(state));
        assertFalse(limiter.tryAcquire(state));
    }
}
