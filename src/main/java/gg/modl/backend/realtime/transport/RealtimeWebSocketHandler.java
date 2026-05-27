package gg.modl.backend.realtime.transport;

import com.google.protobuf.InvalidProtocolBufferException;
import gg.modl.backend.realtime.auth.RealtimeAuthenticationException;
import gg.modl.backend.realtime.auth.RealtimeAuthenticator;
import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.auth.RealtimeTopicAuthorizer;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.rate.RealtimeMessageRateLimiter;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.proto.modl.v1.ClientHello;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.Topic;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

@Component
@RequiredArgsConstructor
public class RealtimeWebSocketHandler extends BinaryWebSocketHandler {
    private static final CloseStatus POLICY_VIOLATION = new CloseStatus(1008);
    private static final CloseStatus UNSUPPORTED_DATA = new CloseStatus(1003);
    private static final CloseStatus MESSAGE_TOO_BIG = new CloseStatus(1009);
    private static final CloseStatus TRY_AGAIN_LATER = new CloseStatus(1013);

    private final RealtimeProperties properties;
    private final RealtimeCodec codec;
    private final RealtimeAuthenticator authenticator;
    private final RealtimeTopicAuthorizer topicAuthorizer;
    private final RealtimeConnectionRegistry connectionRegistry;
    private final RealtimeMessageRateLimiter rateLimiter;
    private final RealtimeConnectionCleanup connectionCleanup;
    private final RealtimeMetrics metrics;
    private final RealtimeSessionOperations sessionOperations;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        RealtimeConnectionState state = connectionRegistry.register(session);
        session.getAttributes().put("realtime.connectionId", state.getConnectionId());
        metrics.recordConnect(state);

        if (!properties.isEnabled()) {
            metrics.recordReject(state, "disabled");
            sessionOperations.requestClose(session, state, TRY_AGAIN_LATER.withReason("Realtime WebSocket is disabled"), "disabled");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        RealtimeConnectionState state = stateForIncomingFrame(session);
        if (state == null) {
            return;
        }
        if (!rateLimiter.tryAcquire(state)) {
            metrics.recordReject(state, "rate_limited");
            closeWithError(session, state, ErrorCode.ERROR_CODE_RATE_LIMITED, "Realtime client message rate limit exceeded", POLICY_VIOLATION);
            return;
        }

        if (!properties.isEnabled()) {
            metrics.recordReject(state, "disabled");
            closeWithError(session, state, ErrorCode.ERROR_CODE_FORBIDDEN, "Realtime WebSocket is disabled", TRY_AGAIN_LATER);
            return;
        }

        RealtimeEnvelope envelope;
        try {
            envelope = codec.parse(message);
        } catch (OversizedRealtimeFrameException exception) {
            metrics.recordReject(state, "oversized_frame");
            closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "Realtime frame is too large", MESSAGE_TOO_BIG);
            return;
        } catch (InvalidProtocolBufferException exception) {
            metrics.recordReject(state, "malformed_frame");
            closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "Malformed realtime protobuf frame", UNSUPPORTED_DATA);
            return;
        }

        if (!state.isAuthenticated()) {
            handleHandshake(session, state, envelope);
            return;
        }

        handleAuthenticatedMessage(session, state, envelope);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        RealtimeConnectionState state = stateForIncomingFrame(session);
        if (state == null) {
            return;
        }
        metrics.recordReject(state, "text_frame");
        try {
            closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "Realtime WebSocket accepts binary protobuf frames only", UNSUPPORTED_DATA);
        } catch (IOException exception) {
            connectionCleanup.unregisterAfterTransportError(session, CloseStatus.SERVER_ERROR, exception);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        RealtimeConnectionState state = stateForIncomingFrame(session);
        if (state == null) {
            return;
        }
        connectionCleanup.recordTransportError(state, exception);
        if (session.isOpen()) {
            sessionOperations.requestClose(session, state, CloseStatus.SERVER_ERROR, "transport_error");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connectionCleanup.unregister(session, status);
    }

    private void handleHandshake(WebSocketSession session, RealtimeConnectionState state, RealtimeEnvelope envelope) throws IOException {
        if (envelope.getPayloadCase() != RealtimeEnvelope.PayloadCase.CLIENT_HELLO) {
            metrics.recordReject(state, "missing_client_hello");
            closeWithError(session, state, ErrorCode.ERROR_CODE_UNAUTHORIZED, "ClientHello is required before other realtime messages", POLICY_VIOLATION);
            return;
        }

        ClientHello hello = envelope.getClientHello();
        int requestedProtocolVersion = hello.getProtocolVersion() != 0
            ? hello.getProtocolVersion()
            : envelope.getProtocolVersion();
        if (requestedProtocolVersion != properties.getProtocolVersion()) {
            metrics.recordReject(state, "unsupported_protocol");
            closeWithError(session, state, ErrorCode.ERROR_CODE_UNSUPPORTED_PROTOCOL, "Unsupported realtime protocol version", POLICY_VIOLATION);
            return;
        }

        RealtimePrincipal principal;
        try {
            principal = authenticator.authenticate(session.getHandshakeHeaders(), hello);
        } catch (RealtimeAuthenticationException exception) {
            metrics.recordAuthFailure(exception.getMessage());
            closeWithError(session, state, exception.getErrorCode(), exception.getMessage(), POLICY_VIOLATION);
            return;
        }

        state.authenticate(principal, requestedProtocolVersion);
        for (Topic topic : hello.getSupportedTopicsList()) {
            if (topicAuthorizer.canSubscribe(principal, topic)) {
                state.subscribe(topic);
            } else {
                metrics.recordTopicAuthorizationFailure(state, topic, "handshake");
            }
        }

        sessionOperations.send(session, state, codec.serverHello(state.getConnectionId(), state.getSubscriptions()));
    }

    private RealtimeConnectionState stateForIncomingFrame(WebSocketSession session) {
        return connectionRegistry.get(session).orElseGet(() -> {
            if (connectionRegistry.isTerminal(session)) {
                return null;
            }
            return connectionRegistry.register(session);
        });
    }

    private void handleAuthenticatedMessage(WebSocketSession session, RealtimeConnectionState state, RealtimeEnvelope envelope) throws IOException {
        if (envelope.getProtocolVersion() != 0 && envelope.getProtocolVersion() != properties.getProtocolVersion()) {
            metrics.recordReject(state, "unsupported_protocol");
            closeWithError(session, state, ErrorCode.ERROR_CODE_UNSUPPORTED_PROTOCOL, "Unsupported realtime protocol version", POLICY_VIOLATION);
            return;
        }

        switch (envelope.getPayloadCase()) {
            case HEARTBEAT -> {
                state.recordHeartbeat();
            }
            case ACK -> {
                state.setLastAcknowledgedEventId(envelope.getAck().getEventId());
                metrics.recordAck(state, envelope.getAck().getEventId());
            }
            case SUBSCRIBE -> subscribe(session, state, envelope.getSubscribe().getTopicsList());
            case UNSUBSCRIBE -> envelope.getUnsubscribe().getTopicsList().forEach(state::unsubscribe);
            case CLIENT_HELLO -> {
                metrics.recordReject(state, "duplicate_client_hello");
                closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "ClientHello may only be sent once", POLICY_VIOLATION);
            }
            default -> {
                metrics.recordReject(state, "unsupported_message");
                closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "Unsupported realtime message type", POLICY_VIOLATION);
            }
        }
    }

    private void subscribe(WebSocketSession session, RealtimeConnectionState state, List<Topic> topics) throws IOException {
        for (Topic topic : topics) {
            if (!topicAuthorizer.canSubscribe(state.getPrincipal(), topic)) {
                metrics.recordTopicAuthorizationFailure(state, topic, "subscribe");
                closeWithError(session, state, ErrorCode.ERROR_CODE_FORBIDDEN, "Topic is not authorized", POLICY_VIOLATION);
                return;
            }
        }
        topics.forEach(state::subscribe);
    }

    private void closeWithError(WebSocketSession session, RealtimeConnectionState state, ErrorCode code, String message, CloseStatus status) throws IOException {
        if (session.isOpen()) {
            sessionOperations.send(session, state, codec.error(code, message));
            sessionOperations.requestClose(session, state, status.withReason(message), "error");
        }
    }
}
