package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.player.controller.v3.MinecraftSyncV3Controller;
import gg.modl.backend.player.service.MinecraftStartupService;
import gg.modl.backend.player.service.MinecraftSyncService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.SimplePunishment;
import gg.modl.proto.modl.v1.StartupRequest;
import gg.modl.proto.modl.v1.StartupResponse;
import gg.modl.proto.modl.v1.SyncData;
import gg.modl.proto.modl.v1.SyncOnlinePlayer;
import gg.modl.proto.modl.v1.SyncPendingPunishment;
import gg.modl.proto.modl.v1.SyncRequest;
import gg.modl.proto.modl.v1.SyncResponse;
import gg.modl.proto.modl.v1.SyncServerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftSyncV3ControllerTest {
    @Mock private MinecraftSyncService minecraftSyncService;
    @Mock private MinecraftStartupService minecraftStartupService;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        mockMvc = MockMvcBuilders.standaloneSetup(new MinecraftSyncV3Controller(minecraftSyncService, minecraftStartupService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(post("/")
                .requestAttr(RequestAttribute.SERVER, server)
                .header("X-Forwarded-For", "203.0.113.20"))
            .build();
    }

    @Test
    void v3StartupReturnsBinaryResponseWithRealtimeBootstrap() throws Exception {
        when(minecraftStartupService.handleStartup(
            same(server),
            eq("1.21.8"),
            eq("paper"),
            eq("2.0.0"),
            eq(200),
            eq("hub"),
            eq("127.0.0.1")
        )).thenReturn(StartupResponse.newBuilder()
            .setPanelUrl("https://demo.modl.gg")
            .setTimestamp("2026-05-12T00:00:00Z")
            .setServerInstanceId("instance-1")
            .setRealtimeEnabled(true)
            .setRealtimeUrl("wss://api.modl.gg/v3/realtime")
            .setRealtimeProtocolVersion(1)
            .addRealtimeTopics("TOPIC_MINECRAFT_PERMISSIONS")
            .addRealtimeTopics("TOPIC_MINECRAFT_PUNISHMENT_TYPES")
            .build());

        StartupRequest request = StartupRequest.newBuilder()
            .setServerVersion("1.21.8")
            .setPlatformType("paper")
            .setPluginVersion("2.0.0")
            .setMaxPlayers(200)
            .setServerName("hub")
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/startup")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        StartupResponse response = StartupResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals("https://demo.modl.gg", response.getPanelUrl());
        assertEquals("2026-05-12T00:00:00Z", response.getTimestamp());
        assertEquals("instance-1", response.getServerInstanceId());
        assertTrue(response.getRealtimeEnabled());
        assertEquals("wss://api.modl.gg/v3/realtime", response.getRealtimeUrl());
        assertEquals(1, response.getRealtimeProtocolVersion());
        assertEquals(2, response.getRealtimeTopicsCount());
        assertEquals("TOPIC_MINECRAFT_PERMISSIONS", response.getRealtimeTopics(0));
        assertEquals("TOPIC_MINECRAFT_PUNISHMENT_TYPES", response.getRealtimeTopics(1));
    }

    @Test
    void v3SyncAcceptsBinaryRequestAndReturnsBinaryResponse() throws Exception {
        SyncResponse stubbedResponse = SyncResponse.newBuilder()
            .setTimestamp("2026-05-12T00:00:01Z")
            .setData(SyncData.newBuilder()
                .addPendingPunishments(SyncPendingPunishment.newBuilder()
                    .setMinecraftUuid("11111111-2222-3333-4444-555555555555")
                    .setUsername("Byteful")
                    .setPunishment(SimplePunishment.newBuilder()
                        .setType("Ban")
                        .setDescription("Rule violation")
                        .setId("punishment-1")
                        .setStarted(true)
                        .setOrdinal(2)
                        .build())
                    .build())
                .setStaffPermissionsUpdatedAt(1_700_000_000_000L)
                .setPunishmentTypesUpdatedAt(1_700_000_001_000L)
                .build())
            .build();

        when(minecraftSyncService.sync(
            same(server),
            eq("2026-05-12T00:00:00Z"),
            any(),
            eq("hub"),
            any(),
            any(),
            any(),
            eq("127.0.0.1")
        )).thenReturn(stubbedResponse);

        SyncRequest request = SyncRequest.newBuilder()
            .setLastSyncTimestamp("2026-05-12T00:00:00Z")
            .setServerName("hub")
            .addOnlinePlayers(SyncOnlinePlayer.newBuilder()
                .setUuid("11111111-2222-3333-4444-555555555555")
                .setUsername("Byteful")
                .setIpAddress("198.51.100.15")
                .build())
            .setServerStatus(SyncServerStatus.newBuilder()
                .setOnlinePlayerCount(1)
                .setMaxPlayers(200)
                .setServerVersion("1.21.8")
                .setPlatformType("paper")
                .setPluginVersion("2.0.0")
                .build())
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/sync")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        SyncResponse response = SyncResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals("2026-05-12T00:00:01Z", response.getTimestamp());
        assertEquals("11111111-2222-3333-4444-555555555555", response.getData().getPendingPunishments(0).getMinecraftUuid());
        assertEquals("Ban", response.getData().getPendingPunishments(0).getPunishment().getType());
        assertEquals(1_700_000_000_000L, response.getData().getStaffPermissionsUpdatedAt());
        assertEquals(1_700_000_001_000L, response.getData().getPunishmentTypesUpdatedAt());

        verify(minecraftSyncService).sync(
            same(server),
            eq("2026-05-12T00:00:00Z"),
            any(),
            eq("hub"),
            any(),
            any(),
            any(),
            eq("127.0.0.1")
        );
    }
}
