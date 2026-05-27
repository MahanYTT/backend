package gg.modl.backend.realtime.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.rate.RealtimeMessageRateLimiter;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.transport.RealtimeCodec;
import gg.modl.backend.realtime.transport.RealtimeSessionOperations;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ReconnectAction;
import gg.modl.proto.modl.v1.ReconnectReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class RealtimeDeployDrainManagerTest {

    @Test
    void deployDrainSendsReconnectAdviceAndClosesWithConfiguredCode() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setDeployDrainCloseCode(1012);
        properties.setDeployDrainRetryAfterMs(7000);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(meterRegistry);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeDeployDrainManager manager = new RealtimeDeployDrainManager(
            properties,
            registry,
            cleanup,
            new RealtimeCodec(properties),
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("deploy-drain", true);
        when(session.isOpen()).thenReturn(true, true, true, false);
        RealtimeConnectionState state = registry.register(session);
        rateLimiter.tryAcquire(state);

        manager.drainForDeploy();

        verify(session).sendMessage(argThat(message -> {
            try {
                RealtimeEnvelope envelope = RealtimeEnvelope.parseFrom(((BinaryMessage) message).getPayload());
                return envelope.hasReconnectAdvice()
                    && envelope.getReconnectAdvice().getReason() == ReconnectReason.RECONNECT_REASON_DEPLOYMENT
                    && envelope.getReconnectAdvice().getAction() == ReconnectAction.RECONNECT_ACTION_RECONNECT
                    && envelope.getReconnectAdvice().getRetryAfterMs() == 7000;
            } catch (Exception exception) {
                return false;
            }
        }));
        verify(session).close(new CloseStatus(1012, "Realtime deploy drain"));
        assertTrue(registry.get(session).isEmpty());
        assertEquals(1.0, meterRegistry.counter("modl.realtime.events", "event", "reconnect_close", "reason", "deploy_drain").count());
    }

    @Test
    void deployDrainCleansUpWhenCloseThrowsAfterSessionCloses() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setDeployDrainCloseCode(1012);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeDeployDrainManager manager = new RealtimeDeployDrainManager(
            properties,
            registry,
            cleanup,
            new RealtimeCodec(properties),
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("deploy-drain-close-throws", true);
        when(session.isOpen()).thenReturn(true, true, true, false);
        CloseStatus closeStatus = new CloseStatus(1012, "Realtime deploy drain");
        doThrow(new java.io.IOException("stalled close")).when(session).close(closeStatus);
        RealtimeConnectionState state = registry.register(session);
        rateLimiter.tryAcquire(state);

        manager.drainForDeploy();

        assertTrue(registry.get(session).isEmpty());
        assertTrue(rateLimiter.tryAcquire(state));
    }

    @Test
    void deployDrainCleansUpClosedSessionsWithoutSendingAdvice() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(meterRegistry);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeDeployDrainManager manager = new RealtimeDeployDrainManager(
            properties,
            registry,
            cleanup,
            new RealtimeCodec(properties),
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("closed", false);
        RealtimeConnectionState state = registry.register(session);
        rateLimiter.tryAcquire(state);

        manager.drainForDeploy();

        verify(session).isOpen();
        verify(session, org.mockito.Mockito.never()).sendMessage(any());
        assertTrue(rateLimiter.tryAcquire(state));
    }

    private WebSocketSession session(String id, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return session;
    }
}
