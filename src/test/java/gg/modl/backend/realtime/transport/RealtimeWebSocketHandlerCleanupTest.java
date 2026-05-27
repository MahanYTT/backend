package gg.modl.backend.realtime.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.realtime.auth.RealtimeAuthenticator;
import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.auth.RealtimeTopicAuthorizer;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.rate.RealtimeMessageRateLimiter;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.Heartbeat;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class RealtimeWebSocketHandlerCleanupTest {

    @Test
    void transportErrorRecordsFailureAndNormalCleanupOnce() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setInboundRateLimitMessages(1);
        properties.setInboundRateLimitWindowSeconds(60);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(meterRegistry);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(
            properties,
            mock(RealtimeCodec.class),
            mock(RealtimeAuthenticator.class),
            mock(RealtimeTopicAuthorizer.class),
            registry,
            rateLimiter,
            cleanup,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("transport-error");
        when(session.isOpen()).thenReturn(true, true, false);
        RealtimeConnectionState state = registry.register(session);
        rateLimiter.tryAcquire(state);

        handler.handleTransportError(session, new IllegalStateException("broken transport"));
        assertTrue(registry.get(session).isEmpty());
        handler.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        verify(session).close(CloseStatus.SERVER_ERROR);
        assertTrue(rateLimiter.tryAcquire(state));
        assertEquals(1.0, meterRegistry.counter("modl.realtime.events", "event", "transport_error").count());
        assertEquals(1.0, meterRegistry.counter("modl.realtime.events", "event", "disconnect").count());
    }

    @Test
    void transportErrorCleansUpWhenCloseThrowsAfterSessionCloses() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setInboundRateLimitMessages(1);
        properties.setInboundRateLimitWindowSeconds(60);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(meterRegistry);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(
            properties,
            mock(RealtimeCodec.class),
            mock(RealtimeAuthenticator.class),
            mock(RealtimeTopicAuthorizer.class),
            registry,
            rateLimiter,
            cleanup,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("transport-error-close-throws");
        when(session.isOpen()).thenReturn(true, true, false);
        doThrow(new java.io.IOException("stalled close")).when(session).close(CloseStatus.SERVER_ERROR);
        RealtimeConnectionState state = registry.register(session);
        rateLimiter.tryAcquire(state);

        handler.handleTransportError(session, new IllegalStateException("broken transport"));

        assertTrue(registry.get(session).isEmpty());
        assertTrue(rateLimiter.tryAcquire(state));
        assertEquals(1.0, meterRegistry.counter("modl.realtime.events", "event", "transport_error").count());
        assertEquals(1.0, meterRegistry.counter("modl.realtime.events", "event", "disconnect").count());
    }

    @Test
    void frameAfterFatalCloseDoesNotReregisterSameSession() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setEnabled(true);
        properties.setInboundRateLimitMessages(1);
        properties.setInboundRateLimitWindowSeconds(60);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(
            properties,
            new RealtimeCodec(properties),
            mock(RealtimeAuthenticator.class),
            mock(RealtimeTopicAuthorizer.class),
            registry,
            rateLimiter,
            cleanup,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("terminal-frame");
        doThrow(new java.io.IOException("stalled close")).when(session).close(CloseStatus.SERVER_ERROR);
        RealtimeConnectionState state = registry.register(session);
        state.authenticate(RealtimePrincipal.minecraft(server()), 1);

        handler.handleTransportError(session, new IllegalStateException("broken transport"));
        handler.handleBinaryMessage(session, new BinaryMessage(RealtimeEnvelope.newBuilder()
            .setProtocolVersion(1)
            .setHeartbeat(Heartbeat.newBuilder().setSequence(13))
            .build()
            .toByteArray()));

        assertSame(state, registry.get(session).orElseThrow());
        assertTrue(state.isClosing());
        assertTrue(registry.isTerminal(session));
    }

    @Test
    void closeFailureLeavesOpenTerminalSessionManaged() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setInboundRateLimitMessages(1);
        properties.setInboundRateLimitWindowSeconds(60);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeSessionOperations sessionOperations = new RealtimeSessionOperations(registry, cleanup, metrics);
        WebSocketSession session = session("close-throws-open");
        doThrow(new java.io.IOException("stalled close")).when(session).close(CloseStatus.SERVER_ERROR);
        RealtimeConnectionState state = registry.register(session);
        assertTrue(rateLimiter.tryAcquire(state));

        assertFalse(sessionOperations.requestClose(session, state, CloseStatus.SERVER_ERROR, "transport_error"));

        assertSame(state, registry.get(session).orElseThrow());
        assertTrue(state.isClosing());
        assertTrue(registry.isTerminal(session));
        assertFalse(rateLimiter.tryAcquire(state));
    }

    @Test
    void closeFailureCleansUpWhenSessionIsClosedAfterFailure() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setInboundRateLimitMessages(1);
        properties.setInboundRateLimitWindowSeconds(60);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeSessionOperations sessionOperations = new RealtimeSessionOperations(registry, cleanup, metrics);
        WebSocketSession session = session("close-throws-closed");
        when(session.isOpen()).thenReturn(true, false);
        doThrow(new java.io.IOException("stalled close")).when(session).close(CloseStatus.SERVER_ERROR);
        RealtimeConnectionState state = registry.register(session);
        assertTrue(rateLimiter.tryAcquire(state));

        assertTrue(sessionOperations.requestClose(session, state, CloseStatus.SERVER_ERROR, "transport_error"));

        assertTrue(registry.get(session).isEmpty());
        assertTrue(registry.isTerminal(session));
        assertTrue(rateLimiter.tryAcquire(state));
    }

    @Test
    void clientHeartbeatOnlyUpdatesStateWithoutEcho() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setEnabled(true);
        properties.setInboundRateLimitMessages(10);
        properties.setInboundRateLimitWindowSeconds(60);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(
            properties,
            new RealtimeCodec(properties),
            mock(RealtimeAuthenticator.class),
            mock(RealtimeTopicAuthorizer.class),
            registry,
            rateLimiter,
            cleanup,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics)
        );
        WebSocketSession session = session("heartbeat");
        RealtimeConnectionState state = registry.register(session);
        state.authenticate(RealtimePrincipal.minecraft(server()), 1);
        RealtimeEnvelope envelope = RealtimeEnvelope.newBuilder()
            .setProtocolVersion(1)
            .setHeartbeat(Heartbeat.newBuilder().setSequence(12))
            .build();

        handler.handleBinaryMessage(session, new BinaryMessage(envelope.toByteArray()));

        verify(session, never()).sendMessage(org.mockito.ArgumentMatchers.any());
    }

    private Server server() {
        Server server = new Server("server", "server", "server_db", "admin@example.com", true, ServerPlan.FREE);
        server.setId("server-id");
        return server;
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn(id);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return session;
    }
}
