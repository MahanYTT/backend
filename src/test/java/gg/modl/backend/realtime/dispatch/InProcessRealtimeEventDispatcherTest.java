package gg.modl.backend.realtime.dispatch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.rate.RealtimeMessageRateLimiter;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.transport.RealtimeCodec;
import gg.modl.backend.realtime.transport.RealtimeSessionOperations;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.PermissionInvalidatedEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.TicketChangedEvent;
import gg.modl.proto.modl.v1.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class InProcessRealtimeEventDispatcherTest {

    @Test
    void publishesOnlyToAuthenticatedSessionsOnMatchingServerAndTopic() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeCodec codec = new RealtimeCodec(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        InProcessRealtimeEventDispatcher dispatcher = new InProcessRealtimeEventDispatcher(
            registry,
            codec,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics),
            new RealtimeTopicPayloadValidator()
        );

        WebSocketSession matchingSession = openSession("matching");
        registry.register(matchingSession).authenticate(RealtimePrincipal.panel(server("server-a"), "staff@example.com"), 1);
        registry.get(matchingSession).orElseThrow().subscribe(Topic.TOPIC_PANEL_TICKETS);

        WebSocketSession wrongTopicSession = openSession("wrong-topic");
        registry.register(wrongTopicSession).authenticate(RealtimePrincipal.panel(server("server-a"), "staff@example.com"), 1);

        WebSocketSession wrongServerSession = openSession("wrong-server");
        registry.register(wrongServerSession).authenticate(RealtimePrincipal.panel(server("server-b"), "staff@example.com"), 1);
        registry.get(wrongServerSession).orElseThrow().subscribe(Topic.TOPIC_PANEL_TICKETS);

        RealtimeEnvelope envelope = RealtimeEnvelope.newBuilder()
            .setTicketChanged(TicketChangedEvent.newBuilder().setTicketId("ticket-1").build())
            .build();

        dispatcher.publish(new RealtimeOutboundEvent("server-a", Topic.TOPIC_PANEL_TICKETS, envelope));

        verify(matchingSession).sendMessage(any(BinaryMessage.class));
        verify(wrongTopicSession, never()).sendMessage(any(BinaryMessage.class));
        verify(wrongServerSession, never()).sendMessage(any(BinaryMessage.class));
    }

    @Test
    void failedSendIsReportedAndSessionIsTerminallyCleanedUp() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeCodec codec = new RealtimeCodec(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        InProcessRealtimeEventDispatcher dispatcher = new InProcessRealtimeEventDispatcher(
            registry,
            codec,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics),
            new RealtimeTopicPayloadValidator()
        );
        WebSocketSession session = openSession("slow-client");
        when(session.isOpen()).thenReturn(true, true, true, false);
        doThrow(new java.io.IOException("stalled send")).when(session).sendMessage(any(BinaryMessage.class));
        doThrow(new java.io.IOException("stalled close")).when(session).close(CloseStatus.SERVER_ERROR);
        RealtimeConnectionState state = registry.register(session);
        state.authenticate(RealtimePrincipal.panel(server("server-a"), "staff@example.com"), 1);
        state.subscribe(Topic.TOPIC_PANEL_TICKETS);

        RealtimeEnvelope envelope = RealtimeEnvelope.newBuilder()
            .setTicketChanged(TicketChangedEvent.newBuilder().setTicketId("ticket-1").build())
            .build();

        RealtimeDispatchResult result = dispatcher.publish(new RealtimeOutboundEvent("server-a", Topic.TOPIC_PANEL_TICKETS, envelope));

        assertEquals(1, result.matchedConnections());
        assertEquals(0, result.deliveredConnections());
        assertEquals(1, result.failedConnections());
        assertTrue(registry.get(session).isEmpty());
        verify(session).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void terminalOpenSessionAfterFailedCloseIsNotMatchedOrUnregisteredByLaterPublish() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeCodec codec = new RealtimeCodec(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        InProcessRealtimeEventDispatcher dispatcher = new InProcessRealtimeEventDispatcher(
            registry,
            codec,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics),
            new RealtimeTopicPayloadValidator()
        );
        WebSocketSession session = openSession("close-throws-open");
        doThrow(new java.io.IOException("stalled send")).when(session).sendMessage(any(BinaryMessage.class));
        doThrow(new java.io.IOException("stalled close")).when(session).close(CloseStatus.SERVER_ERROR);
        RealtimeConnectionState state = registry.register(session);
        state.authenticate(RealtimePrincipal.panel(server("server-a"), "staff@example.com"), 1);
        state.subscribe(Topic.TOPIC_PANEL_TICKETS);

        RealtimeEnvelope envelope = RealtimeEnvelope.newBuilder()
            .setTicketChanged(TicketChangedEvent.newBuilder().setTicketId("ticket-1").build())
            .build();

        RealtimeDispatchResult failedCloseResult = dispatcher.publish(new RealtimeOutboundEvent("server-a", Topic.TOPIC_PANEL_TICKETS, envelope));

        assertEquals(1, failedCloseResult.matchedConnections());
        assertEquals(0, failedCloseResult.deliveredConnections());
        assertEquals(1, failedCloseResult.failedConnections());
        assertSame(state, registry.get(session).orElseThrow());
        assertTrue(state.isClosing());
        assertTrue(registry.isTerminal(session));
        verify(session).sendMessage(any(BinaryMessage.class));

        RealtimeDispatchResult laterResult = dispatcher.publish(new RealtimeOutboundEvent("server-a", Topic.TOPIC_PANEL_TICKETS, envelope));

        assertEquals(0, laterResult.matchedConnections());
        assertEquals(0, laterResult.deliveredConnections());
        assertEquals(0, laterResult.failedConnections());
        assertSame(state, registry.get(session).orElseThrow());
        assertTrue(registry.isTerminal(session));
        assertFalse(registry.get(session).isEmpty());
        verify(session, times(1)).sendMessage(any(BinaryMessage.class));
    }

    @Test
    void eachMatchingSessionReceivesFullPayloadBytes() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeCodec codec = new RealtimeCodec(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        InProcessRealtimeEventDispatcher dispatcher = new InProcessRealtimeEventDispatcher(
            registry,
            codec,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics),
            new RealtimeTopicPayloadValidator()
        );

        WebSocketSession firstSession = openSession("first");
        RealtimeConnectionState firstState = registry.register(firstSession);
        firstState.authenticate(RealtimePrincipal.panel(server("server-a"), "staff@example.com"), 1);
        firstState.subscribe(Topic.TOPIC_PANEL_TICKETS);

        WebSocketSession secondSession = openSession("second");
        RealtimeConnectionState secondState = registry.register(secondSession);
        secondState.authenticate(RealtimePrincipal.panel(server("server-a"), "staff@example.com"), 1);
        secondState.subscribe(Topic.TOPIC_PANEL_TICKETS);

        RealtimeEnvelope envelope = RealtimeEnvelope.newBuilder()
            .setTicketChanged(TicketChangedEvent.newBuilder().setTicketId("ticket-1").build())
            .build();

        RealtimeDispatchResult result = dispatcher.publish(new RealtimeOutboundEvent("server-a", Topic.TOPIC_PANEL_TICKETS, envelope));

        assertEquals(2, result.matchedConnections());
        assertEquals(2, result.deliveredConnections());

        ArgumentCaptor<BinaryMessage> firstCaptor = ArgumentCaptor.forClass(BinaryMessage.class);
        ArgumentCaptor<BinaryMessage> secondCaptor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(firstSession).sendMessage(firstCaptor.capture());
        verify(secondSession).sendMessage(secondCaptor.capture());

        byte[] firstPayload = payload(firstCaptor.getValue());
        byte[] secondPayload = payload(secondCaptor.getValue());
        assertArrayEquals(firstPayload, secondPayload);
        assertEquals("ticket-1", RealtimeEnvelope.parseFrom(firstPayload).getTicketChanged().getTicketId());
        assertEquals("ticket-1", RealtimeEnvelope.parseFrom(secondPayload).getTicketChanged().getTicketId());
    }

    @Test
    void invalidTopicPayloadCombinationIsDroppedBeforeDelivery() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeCodec codec = new RealtimeCodec(properties);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(meterRegistry);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        InProcessRealtimeEventDispatcher dispatcher = new InProcessRealtimeEventDispatcher(
            registry,
            codec,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics),
            new RealtimeTopicPayloadValidator()
        );
        WebSocketSession session = openSession("matching");
        RealtimeConnectionState state = registry.register(session);
        state.authenticate(RealtimePrincipal.panel(server("server-a"), "staff@example.com"), 1);
        state.subscribe(Topic.TOPIC_PANEL_TICKETS);

        RealtimeEnvelope envelope = RealtimeEnvelope.newBuilder()
            .setPermissionInvalidated(PermissionInvalidatedEvent.newBuilder().build())
            .build();

        RealtimeDispatchResult result = dispatcher.publish(new RealtimeOutboundEvent("server-a", Topic.TOPIC_PANEL_TICKETS, envelope));

        assertEquals(0, result.matchedConnections());
        assertEquals(0, result.deliveredConnections());
        assertEquals(0, result.failedConnections());
        verify(session, never()).sendMessage(any(BinaryMessage.class));
        assertEquals(1.0, meterRegistry.counter(
            "modl.realtime.events",
            "event",
            "invalid_outbound_payload",
            "topic",
            Topic.TOPIC_PANEL_TICKETS.name()
        ).count());
    }

    @Test
    void validMinecraftInvalidationPayloadIsDelivered() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeCodec codec = new RealtimeCodec(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, metrics);
        InProcessRealtimeEventDispatcher dispatcher = new InProcessRealtimeEventDispatcher(
            registry,
            codec,
            metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics),
            new RealtimeTopicPayloadValidator()
        );
        WebSocketSession session = openSession("minecraft");
        RealtimeConnectionState state = registry.register(session);
        state.authenticate(RealtimePrincipal.minecraft(server("server-a")), 1);
        state.subscribe(Topic.TOPIC_MINECRAFT_PERMISSIONS);

        RealtimeEnvelope envelope = RealtimeEnvelope.newBuilder()
            .setPermissionInvalidated(PermissionInvalidatedEvent.newBuilder().build())
            .build();

        RealtimeDispatchResult result = dispatcher.publish(new RealtimeOutboundEvent("server-a", Topic.TOPIC_MINECRAFT_PERMISSIONS, envelope));

        assertEquals(1, result.matchedConnections());
        assertEquals(1, result.deliveredConnections());
        verify(session).sendMessage(any(BinaryMessage.class));
    }

    private byte[] payload(BinaryMessage message) {
        byte[] payload = new byte[message.getPayloadLength()];
        message.getPayload().get(payload);
        return payload;
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn(id);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return session;
    }

    private Server server(String id) {
        Server server = new Server("server", "server", "server_db", "admin@example.com", true, ServerPlan.FREE);
        server.setId(id);
        return server;
    }
}
