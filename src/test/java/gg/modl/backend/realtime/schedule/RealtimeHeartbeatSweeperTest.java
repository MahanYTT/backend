package gg.modl.backend.realtime.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.rate.RealtimeMessageRateLimiter;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.transport.RealtimeSessionOperations;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class RealtimeHeartbeatSweeperTest {

    @Test
    void clearsRateLimitWindowWhenRemovingClosedSession() {
        RealtimeProperties properties = properties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeHeartbeatSweeper sweeper = new RealtimeHeartbeatSweeper(
            properties,
            registry,
            new RealtimeConnectionCleanup(registry, rateLimiter, metrics),
            metrics,
            new RealtimeSessionOperations(registry, new RealtimeConnectionCleanup(registry, rateLimiter, metrics), metrics)
        );
        WebSocketSession session = session("closed", false);
        RealtimeConnectionState state = registry.register(session);
        rateLimiter.tryAcquire(state);

        sweeper.closeTimedOutSessions();

        assertTrue(rateLimiter.tryAcquire(state));
    }

    @Test
    void heartbeatTimeoutUsesNormalCleanup() throws Exception {
        RealtimeProperties properties = properties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(meterRegistry);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeHeartbeatSweeper sweeper = new RealtimeHeartbeatSweeper(
            properties,
            registry,
            cleanup,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("timeout", true);
        when(session.isOpen()).thenReturn(true, true, false);
        RealtimeConnectionState state = registry.register(session);
        state.authenticate(RealtimePrincipal.minecraft(server()), 1);
        ReflectionTestUtils.setField(state, "lastHeartbeat", Instant.EPOCH);
        rateLimiter.tryAcquire(state);

        sweeper.closeTimedOutSessions();

        verify(session).close(new CloseStatus(1001, "Realtime heartbeat timed out"));
        assertTrue(registry.get(session).isEmpty());
        assertEquals(1.0, meterRegistry.counter("modl.realtime.events", "event", "timeout_close").count());
        assertEquals(1.0, meterRegistry.counter("modl.realtime.events", "event", "disconnect").count());
    }

    @Test
    void unauthenticatedHandshakeTimeoutUsesNormalCleanup() throws Exception {
        RealtimeProperties properties = properties();
        properties.setHandshakeTimeoutSeconds(5);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(meterRegistry);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeHeartbeatSweeper sweeper = new RealtimeHeartbeatSweeper(
            properties,
            registry,
            cleanup,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("unauthenticated", true);
        when(session.isOpen()).thenReturn(true, true, false);
        RealtimeConnectionState state = registry.register(session);
        ReflectionTestUtils.setField(state, "connectedAt", Instant.EPOCH);
        rateLimiter.tryAcquire(state);

        sweeper.closeTimedOutSessions();

        verify(session).close(new CloseStatus(1008, "Realtime ClientHello timed out"));
        assertTrue(registry.get(session).isEmpty());
        assertEquals(1.0, meterRegistry.counter("modl.realtime.events", "event", "reject", "reason", "handshake_timeout").count());
        assertEquals(1.0, meterRegistry.counter("modl.realtime.events", "event", "disconnect").count());
    }

    @Test
    void heartbeatTimeoutCleansUpWhenCloseThrowsAfterSessionCloses() throws Exception {
        RealtimeProperties properties = properties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeHeartbeatSweeper sweeper = new RealtimeHeartbeatSweeper(
            properties,
            registry,
            cleanup,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("timeout-close-throws", true);
        when(session.isOpen()).thenReturn(true, true, false);
        doThrow(new java.io.IOException("stalled close")).when(session).close(new CloseStatus(1001, "Realtime heartbeat timed out"));
        RealtimeConnectionState state = registry.register(session);
        state.authenticate(RealtimePrincipal.minecraft(server()), 1);
        ReflectionTestUtils.setField(state, "lastHeartbeat", Instant.EPOCH);
        rateLimiter.tryAcquire(state);

        sweeper.closeTimedOutSessions();

        assertTrue(registry.get(session).isEmpty());
        assertTrue(rateLimiter.tryAcquire(state));
    }

    @Test
    void handshakeTimeoutCleansUpWhenCloseThrowsAfterSessionCloses() throws Exception {
        RealtimeProperties properties = properties();
        properties.setHandshakeTimeoutSeconds(5);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeHeartbeatSweeper sweeper = new RealtimeHeartbeatSweeper(
            properties,
            registry,
            cleanup,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("handshake-close-throws", true);
        when(session.isOpen()).thenReturn(true, true, false);
        doThrow(new java.io.IOException("stalled close")).when(session).close(new CloseStatus(1008, "Realtime ClientHello timed out"));
        RealtimeConnectionState state = registry.register(session);
        ReflectionTestUtils.setField(state, "connectedAt", Instant.EPOCH);
        rateLimiter.tryAcquire(state);

        sweeper.closeTimedOutSessions();

        assertTrue(registry.get(session).isEmpty());
        assertTrue(rateLimiter.tryAcquire(state));
    }

    @Test
    void unauthenticatedFreshSessionIsLeftOpen() throws Exception {
        RealtimeProperties properties = properties();
        properties.setHandshakeTimeoutSeconds(60);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeHeartbeatSweeper sweeper = new RealtimeHeartbeatSweeper(
            properties,
            registry,
            new RealtimeConnectionCleanup(registry, rateLimiter, metrics),
            metrics,
            new RealtimeSessionOperations(registry, new RealtimeConnectionCleanup(registry, rateLimiter, metrics), metrics)
        );
        WebSocketSession session = session("fresh", true);
        registry.register(session);

        sweeper.closeTimedOutSessions();

        verify(session, org.mockito.Mockito.never()).close(org.mockito.Mockito.any(CloseStatus.class));
        assertTrue(registry.get(session).isPresent());
    }

    private RealtimeProperties properties() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setEnabled(true);
        properties.setInboundRateLimitMessages(1);
        properties.setInboundRateLimitWindowSeconds(60);
        properties.setHeartbeatTimeoutSeconds(60);
        return properties;
    }

    private WebSocketSession session(String id, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return session;
    }

    private Server server() {
        Server server = new Server("server", "server", "server_db", "admin@example.com", true, ServerPlan.FREE);
        server.setId("server-id");
        return server;
    }
}
