package gg.modl.backend.realtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import gg.modl.backend.realtime.transport.RealtimeWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@ExtendWith(MockitoExtension.class)
class RealtimeWebSocketConfigTest {

    @Test
    void containerLimitsMatchRealtimeProperties() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setMaxFrameBytes(32_768);
        properties.setMaxTextFrameBytes(512);
        properties.setIdleTimeoutMs(70_000);
        properties.setAsyncSendTimeoutMs(8_000);
        RealtimeWebSocketConfig config = new RealtimeWebSocketConfig(mock(RealtimeWebSocketHandler.class), properties);

        ServletServerContainerFactoryBean container = config.realtimeWebSocketContainer();

        assertEquals(32_768, container.getMaxBinaryMessageBufferSize());
        assertEquals(512, container.getMaxTextMessageBufferSize());
        assertEquals(70_000, container.getMaxSessionIdleTimeout());
        assertEquals(8_000, container.getAsyncSendTimeout());
    }
}
