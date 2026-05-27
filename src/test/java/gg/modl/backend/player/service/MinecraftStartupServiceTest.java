package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import gg.modl.backend.database.mongo.repository.ServerInstanceSnapshotMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.config.ModlProperties;
import gg.modl.backend.player.controller.v2.MinecraftStartupController.StartupRequest;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.StartupResponse;
import gg.modl.proto.modl.v1.Topic;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MinecraftStartupServiceTest {

    @Test
    void startupKeepsPanelUrlAndOmitsRealtimeUrlWhenDisabled() {
        MinecraftStartupService service = service(properties(false, ""));
        Server server = server();

        StartupResponse response = service.handleStartup(server, request(), "127.0.0.1");

        assertEquals("https://demo.modl.gg", response.getPanelUrl());
        assertNotNull(response.getServerInstanceId());
        assertTrue(response.getServerInstanceId().length() >= 36);
        assertFalse(response.getRealtimeEnabled());
        assertFalse(response.hasRealtimeUrl());
        assertEquals(1, response.getRealtimeProtocolVersion());
        assertIterableEquals(List.of(), response.getRealtimeTopicsList());
    }

    @Test
    void startupExposesRealtimeMetadataWhenBackendEnabledAndUrlConfigured() {
        MinecraftStartupService service = service(properties(true, "wss://api.modl.gg/v1/realtime/ws"));
        Server server = server();

        StartupResponse response = service.handleStartup(server, request(), "127.0.0.1");

        assertEquals("https://demo.modl.gg", response.getPanelUrl());
        assertTrue(response.getRealtimeEnabled());
        assertEquals("wss://api.modl.gg/v1/realtime/ws", response.getRealtimeUrl());
        assertEquals(1, response.getRealtimeProtocolVersion());
        assertIterableEquals(
            List.of(
                Topic.TOPIC_MINECRAFT_PERMISSIONS.name(),
                Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES.name()
            ),
            response.getRealtimeTopicsList()
        );
    }

    @Test
    void startupDoesNotAdvertiseRealtimeWhenUrlIsBlank() {
        MinecraftStartupService service = service(properties(true, " "));
        Server server = server();

        StartupResponse response = service.handleStartup(server, request(), "127.0.0.1");

        assertFalse(response.getRealtimeEnabled());
        assertFalse(response.hasRealtimeUrl());
        assertFalse(response.getRealtimeTopicsList().contains(Topic.TOPIC_MINECRAFT_PUNISHMENTS.name()));
    }

    private MinecraftStartupService service(RealtimeProperties realtimeProperties) {
        ModlProperties modlProperties = new ModlProperties();
        modlProperties.setDomain("modl.gg");
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        ServerInstanceSnapshotMongoRepository snapshotRepository = mock(ServerInstanceSnapshotMongoRepository.class);
        return new MinecraftStartupService(modlProperties, realtimeProperties, serverRepository, snapshotRepository);
    }

    private RealtimeProperties properties(boolean enabled, String publicUrl) {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setEnabled(enabled);
        properties.setProtocolVersion(1);
        properties.setPublicUrl(publicUrl);
        return properties;
    }

    private Server server() {
        Server server = new Server("Demo", "demo", "demo_db", "admin@example.com", true, ServerPlan.FREE);
        server.setId("server-id");
        return server;
    }

    private StartupRequest request() {
        return new StartupRequest("1.21.4", "spigot", "2.2.2", 100, "Lobby");
    }
}
