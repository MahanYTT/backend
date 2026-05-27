package gg.modl.backend.realtime.lifecycle;

import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.transport.RealtimeCodec;
import gg.modl.backend.realtime.transport.RealtimeSessionOperations;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimeDeployDrainManager {
    private static final String DRAIN_REASON = "Realtime deploy drain";

    private final RealtimeProperties properties;
    private final RealtimeConnectionRegistry connectionRegistry;
    private final RealtimeConnectionCleanup connectionCleanup;
    private final RealtimeCodec codec;
    private final RealtimeSessionOperations sessionOperations;

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        drainForDeploy();
    }

    void drainForDeploy() {
        CloseStatus closeStatus = new CloseStatus(properties.getDeployDrainCloseCode(), DRAIN_REASON);
        for (RealtimeConnectionRegistry.RealtimeConnectionSnapshot snapshot : connectionRegistry.snapshot()) {
            WebSocketSession session = snapshot.session();
            RealtimeConnectionState state = snapshot.state();
            if (!session.isOpen()) {
                connectionCleanup.unregister(session, CloseStatus.NO_CLOSE_FRAME);
                continue;
            }

            closeWithAdvice(session, state, closeStatus);
        }
    }

    private void closeWithAdvice(WebSocketSession session, RealtimeConnectionState state, CloseStatus closeStatus) {
        try {
            sessionOperations.send(session, state, codec.deployDrainAdvice());
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to send deploy drain advice to session {}; will still request close: {}",
                session.getId(), e.toString());
        }
        sessionOperations.requestClose(session, state, closeStatus, "deploy_drain");
    }
}
