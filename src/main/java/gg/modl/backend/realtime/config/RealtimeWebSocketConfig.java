package gg.modl.backend.realtime.config;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.realtime.transport.RealtimeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "modl.realtime.ws.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RealtimeWebSocketConfig implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler realtimeWebSocketHandler;
    private final RealtimeProperties properties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeWebSocketHandler, RESTMappingV1.REALTIME_WS)
            .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean realtimeWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(properties.getMaxFrameBytes());
        container.setMaxTextMessageBufferSize(properties.getMaxTextFrameBytes());
        container.setMaxSessionIdleTimeout(properties.getIdleTimeoutMs());
        container.setAsyncSendTimeout(properties.getAsyncSendTimeoutMs());
        return container;
    }
}
