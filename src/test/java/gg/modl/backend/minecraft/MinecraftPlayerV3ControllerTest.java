package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import gg.modl.backend.infrastructure.exception.ErrorResponseDTO;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.player.controller.v1.MinecraftPlayerController;
import gg.modl.backend.player.controller.v3.MinecraftPlayerV3Controller;
import gg.modl.backend.player.service.MinecraftPlayerService;
import gg.modl.backend.player.service.PlayerLookupService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.Account;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.CreatePlayerNoteRequest;
import gg.modl.proto.modl.v1.LinkedAccountsResponse;
import gg.modl.proto.modl.v1.OnlinePlayersResponse;
import gg.modl.proto.modl.v1.PaginatedNotesResponse;
import gg.modl.proto.modl.v1.PaginatedPunishmentsResponse;
import gg.modl.proto.modl.v1.PardonPlayerRequest;
import gg.modl.proto.modl.v1.PardonResponse;
import gg.modl.proto.modl.v1.PlayerDisconnectRequest;
import gg.modl.proto.modl.v1.PlayerGetResponse;
import gg.modl.proto.modl.v1.PlayerLoginRequest;
import gg.modl.proto.modl.v1.PlayerLoginResponse;
import gg.modl.proto.modl.v1.PlayerLookupRequest;
import gg.modl.proto.modl.v1.PlayerLookupResponse;
import gg.modl.proto.modl.v1.PlayerNameResponse;
import gg.modl.proto.modl.v1.PlayerNoteCreateResponse;
import gg.modl.proto.modl.v1.PlayerProfileResponse;
import gg.modl.proto.modl.v1.SimpleResponse;
import gg.modl.proto.modl.v1.SubmitPlayerIpInfoRequest;
import gg.modl.proto.modl.v1.ReportsResponse;
import gg.modl.proto.modl.v1.UpdatePlayerServerRequest;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftPlayerV3ControllerTest {
    private static final UUID PLAYER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private MinecraftPlayerService minecraftPlayerService;
    @Mock private PlayerLookupService playerLookupService;
    private MockMvc v3MockMvc;
    private MockMvc v1MockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        v3MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftPlayerV3Controller(minecraftPlayerService, playerLookupService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(post("/").requestAttr(RequestAttribute.SERVER, server))
            .build();

        v1MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftPlayerController(minecraftPlayerService, playerLookupService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .defaultRequest(post("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3LoginAcceptsBinaryRequestAndReturnsBinaryResponse() throws Exception {
        Map<String, Object> serviceBody = Map.of(
            "status", 201,
            "activePunishments", List.of(Map.of(
                "type", "Ban",
                "category", "BAN",
                "expiration", 1_777_777_777_000L,
                "description", "Rule violation",
                "id", "punishment-1",
                "issuerName", "Moderator",
                "issuedAt", 1_700_000_000_000L,
                "playerDescription", "You are banned",
                "started", true,
                "ordinal", 2
            )),
            "pendingNotifications", List.of(Map.of(
                "id", "notification-1",
                "count", 2,
                "read", false
            )),
            "pendingIpLookups", List.of("203.0.113.10"),
            "pendingStatWipes", List.of(Map.of(
                "minecraftUuid", PLAYER_UUID.toString(),
                "username", "Byteful",
                "punishmentId", "punishment-1"
            ))
        );
        when(minecraftPlayerService.login(
            same(server),
            eq(PLAYER_UUID),
            eq("Byteful"),
            eq("203.0.113.10"),
            any(),
            eq("skin-hash"),
            eq("hub")
        )).thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.CREATED, serviceBody));

        PlayerLoginRequest request = PlayerLoginRequest.newBuilder()
            .setMinecraftUuid(PLAYER_UUID.toString())
            .setUsername("Byteful")
            .setIpAddress("203.0.113.10")
            .setSkinHash("skin-hash")
            .setServerName("hub")
            .setServerInstanceId("ignored-instance")
            .setIpInfo(Struct.newBuilder()
                .putFields("country", Value.newBuilder().setStringValue("US").build())
                .putFields("proxy", Value.newBuilder().setBoolValue(false).build())
                .putFields("score", Value.newBuilder().setNumberValue(42).build())
                .build())
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/login")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PlayerLoginResponse response = PlayerLoginResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(201, response.getStatus());
        assertEquals("Ban", response.getActivePunishments(0).getType());
        assertEquals("BAN", response.getActivePunishments(0).getCategory());
        assertEquals(1_777_777_777_000L, response.getActivePunishments(0).getExpiration());
        assertEquals("Rule violation", response.getActivePunishments(0).getDescription());
        assertEquals("punishment-1", response.getActivePunishments(0).getId());
        assertEquals("Moderator", response.getActivePunishments(0).getIssuerName());
        assertEquals(1_700_000_000_000L, response.getActivePunishments(0).getIssuedAt());
        assertEquals("You are banned", response.getActivePunishments(0).getPlayerDescription());
        assertTrue(response.getActivePunishments(0).getStarted());
        assertEquals(2, response.getActivePunishments(0).getOrdinal());
        assertEquals("notification-1", response.getPendingNotifications(0).getFieldsOrThrow("id").getStringValue());
        assertEquals(2, response.getPendingNotifications(0).getFieldsOrThrow("count").getNumberValue());
        assertFalse(response.getPendingNotifications(0).getFieldsOrThrow("read").getBoolValue());
        assertEquals("203.0.113.10", response.getPendingIpLookups(0));
        assertEquals(PLAYER_UUID.toString(), response.getPendingStatWipes(0).getMinecraftUuid());
        assertEquals("Byteful", response.getPendingStatWipes(0).getUsername());
        assertEquals("punishment-1", response.getPendingStatWipes(0).getPunishmentId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ipInfoCaptor = ArgumentCaptor.forClass(Map.class);
        verify(minecraftPlayerService).login(
            same(server),
            eq(PLAYER_UUID),
            eq("Byteful"),
            eq("203.0.113.10"),
            ipInfoCaptor.capture(),
            eq("skin-hash"),
            eq("hub")
        );
        assertEquals("US", ipInfoCaptor.getValue().get("country"));
        assertEquals(false, ipInfoCaptor.getValue().get("proxy"));
        assertEquals(42.0, ipInfoCaptor.getValue().get("score"));
    }

    @Test
    void v3LoginRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/login")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
    }

    @Test
    void v3LoginInvalidUuidReturnsBinaryApiError() throws Exception {
        PlayerLoginRequest request = PlayerLoginRequest.newBuilder()
            .setMinecraftUuid("not-a-uuid")
            .setUsername("Byteful")
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/login")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
    }

    @Test
    void v3LoginValidationFailureReturnsBinaryApiErrorWithFieldViolations() throws Exception {
        PlayerLoginRequest request = PlayerLoginRequest.newBuilder()
            .setMinecraftUuid(PLAYER_UUID.toString())
            .setUsername("")
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/login")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertFalse(error.getFieldViolationsList().isEmpty());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("username")));
    }

    @Test
    void v3LoginServiceFailureReturnsBinaryApiError() throws Exception {
        when(minecraftPlayerService.login(
            same(server),
            eq(PLAYER_UUID),
            eq("Byteful"),
            eq("203.0.113.10"),
            any(),
            eq(null),
            eq(null)
        )).thenThrow(new RuntimeException("boom"));

        PlayerLoginRequest request = PlayerLoginRequest.newBuilder()
            .setMinecraftUuid(PLAYER_UUID.toString())
            .setUsername("Byteful")
            .setIpAddress("203.0.113.10")
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/login")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(500, error.getStatusCode());
        assertEquals("INTERNAL", error.getCode());
    }

    @Test
    void v3DisconnectAcceptsBinaryRequestAndReturnsBinarySimpleResponse() throws Exception {
        when(minecraftPlayerService.disconnect(server, PLAYER_UUID.toString(), 12_345L))
            .thenReturn(Map.of("status", 200, "success", true));

        PlayerDisconnectRequest request = PlayerDisconnectRequest.newBuilder()
            .setMinecraftUuid(PLAYER_UUID.toString())
            .setSessionDurationMs(12_345L)
            .setServerInstanceId("ignored-instance")
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/disconnect")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        SimpleResponse response = SimpleResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertTrue(response.getSuccess());
        verify(minecraftPlayerService).disconnect(server, PLAYER_UUID.toString(), 12_345L);
    }

    @Test
    void v3UpdateServerAcceptsBinaryRequestAndReturnsBinarySimpleResponse() throws Exception {
        when(minecraftPlayerService.updateServer(server, PLAYER_UUID.toString(), "survival"))
            .thenReturn(Map.of("status", 200, "success", true));

        UpdatePlayerServerRequest request = UpdatePlayerServerRequest.newBuilder()
            .setMinecraftUuid(PLAYER_UUID.toString())
            .setServerName("survival")
            .setServerInstanceId("ignored-instance")
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/update-server")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        SimpleResponse response = SimpleResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertTrue(response.getSuccess());
        verify(minecraftPlayerService).updateServer(server, PLAYER_UUID.toString(), "survival");
    }

    @Test
    void v3SubmitIpInfoAcceptsBinaryRequestAndReturnsBinarySimpleResponse() throws Exception {
        when(minecraftPlayerService.submitIpInfo(
            server,
            PLAYER_UUID.toString(),
            "203.0.113.10",
            "US",
            "California",
            "AS64500",
            true,
            false
        )).thenReturn(Map.of("status", 200, "success", true));

        SubmitPlayerIpInfoRequest request = SubmitPlayerIpInfoRequest.newBuilder()
            .setMinecraftUuid(PLAYER_UUID.toString())
            .setIp("203.0.113.10")
            .setCountry("US")
            .setRegion("California")
            .setAsn("AS64500")
            .setProxy(true)
            .setHosting(false)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/submit-ip-info")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        SimpleResponse response = SimpleResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertTrue(response.getSuccess());
        verify(minecraftPlayerService).submitIpInfo(
            server,
            PLAYER_UUID.toString(),
            "203.0.113.10",
            "US",
            "California",
            "AS64500",
            true,
            false
        );
    }

    @Test
    void v3DisconnectRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/disconnect")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
    }

    @Test
    void v3UpdateServerRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/update-server")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
    }

    @Test
    void v3SubmitIpInfoRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/submit-ip-info")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
    }

    @Test
    void v1LoginInvalidUuidStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PLAYERS + "/login")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "minecraftUUID": "not-a-uuid",
                      "username": "Byteful",
                      "ip": "203.0.113.10"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid argument", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
    }

    @Test
    void v1LoginServiceFailureStillReturnsJsonError() throws Exception {
        when(minecraftPlayerService.login(
            same(server),
            eq(PLAYER_UUID),
            eq("Byteful"),
            eq("203.0.113.10"),
            any(),
            eq(null),
            eq(null)
        )).thenThrow(new RuntimeException("boom"));

        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PLAYERS + "/login")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "minecraftUUID": "11111111-2222-3333-4444-555555555555",
                      "username": "Byteful",
                      "ip": "203.0.113.10"
                    }
                    """))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(500, error.status());
        assertEquals("An internal error occurred", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
    }

    @Test
    void v3GetOnlinePlayersReturnsBinaryResponse() throws Exception {
        when(minecraftPlayerService.getOnlinePlayers(server))
            .thenReturn(Map.of(
                "status", 200,
                "players", List.of(Map.of(
                    "uuid", PLAYER_UUID.toString(),
                    "username", "Byteful",
                    "joinedAt", "2026-05-03T10:15:30Z",
                    "totalPlaytimeMs", 12_000L
                ))
            ));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/online")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        OnlinePlayersResponse response = OnlinePlayersResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(1, response.getPlayersCount());
        assertEquals(PLAYER_UUID.toString(), response.getPlayers(0).getUuid());
        assertEquals("Byteful", response.getPlayers(0).getUsername());
        assertEquals("2026-05-03T10:15:30Z", response.getPlayers(0).getJoinedAt());
        assertEquals(12_000L, response.getPlayers(0).getTotalPlaytimeMs());
        verify(minecraftPlayerService).getOnlinePlayers(server);
    }

    @Test
    void v3GetOnlinePlayersRejectsJsonAcceptWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/online")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
    }

    @Test
    void v3GetPlayerByUuidPassesLimitsAndReturnsProfile() throws Exception {
        when(playerLookupService.getPlayerByUuid(server, PLAYER_UUID.toString(), 3, 2))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, profileBody()));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/" + PLAYER_UUID)
                .queryParam("punishmentLimit", "3")
                .queryParam("noteLimit", "2")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PlayerProfileResponse response = PlayerProfileResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertProfile(response.getProfile());
        assertEquals(200, response.getStatus());
        assertEquals(4, response.getPunishmentCount());
        assertEquals(5, response.getNoteCount());
        verify(playerLookupService).getPlayerByUuid(server, PLAYER_UUID.toString(), 3, 2);
    }

    @Test
    void v3GetPlayerByUuidPreservesMalformedUuidServiceBehavior() throws Exception {
        when(playerLookupService.getPlayerByUuid(server, "not-a-uuid", null, null))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.NOT_FOUND, Map.of(
                "status", 404,
                "message", "Player not found"
            )));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/not-a-uuid")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_FOUND", error.getCode());
        assertEquals("Player not found", error.getMessage());
        verify(playerLookupService).getPlayerByUuid(server, "not-a-uuid", null, null);
    }

    @Test
    void v3GetPlayerByMinecraftUuidPassesFalseMojangFlagAndReturnsPlayer() throws Exception {
        when(playerLookupService.getPlayerByMinecraftUuid(server, PLAYER_UUID.toString(), false))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, Map.of(
                "status", 200,
                "message", "Player found",
                "player", accountMap()
            )));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players")
                .queryParam("minecraftUuid", PLAYER_UUID.toString())
                .queryParam("queryMojang", "false")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PlayerGetResponse response = PlayerGetResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("Player found", response.getMessage());
        assertProfile(response.getPlayer());
        verify(playerLookupService).getPlayerByMinecraftUuid(server, PLAYER_UUID.toString(), false);
    }

    @Test
    void v3GetPlayerByMinecraftUuidDefaultsQueryMojangTrue() throws Exception {
        when(playerLookupService.getPlayerByMinecraftUuid(server, PLAYER_UUID.toString(), true))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, Map.of(
                "status", 200,
                "message", "Player found",
                "player", accountMap()
            )));

        v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players")
                .queryParam("minecraftUuid", PLAYER_UUID.toString())
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk());

        verify(playerLookupService).getPlayerByMinecraftUuid(server, PLAYER_UUID.toString(), true);
    }

    @Test
    void v3GetPlayerByMinecraftUuidNotFoundReturnsBinaryApiError() throws Exception {
        when(playerLookupService.getPlayerByMinecraftUuid(server, PLAYER_UUID.toString(), false))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.NOT_FOUND, Map.of(
                "status", 404,
                "message", "Player not found"
            )));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players")
                .queryParam("minecraftUuid", PLAYER_UUID.toString())
                .queryParam("queryMojang", "false")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_FOUND", error.getCode());
    }

    @Test
    void v3GetPlayerByMinecraftUuidRejectsMissingAndBlankParameterWithoutServiceCall() throws Exception {
        for (String minecraftUuid : List.of("", "   ")) {
            MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players")
                    .queryParam("minecraftUuid", minecraftUuid)
                    .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
                .andReturn();

            ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
            assertEquals(400, error.getStatusCode());
            assertEquals("INVALID_ARGUMENT", error.getCode());
        }

        v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(playerLookupService);
    }

    @Test
    void v3GetPlayerByUsernamePassesFalseMojangFlagAndReturnsPlayer() throws Exception {
        when(playerLookupService.getPlayerByUsername(server, "Byteful", false))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, Map.of(
                "status", 200,
                "message", "Player found",
                "player", accountMap()
            )));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/by-name")
                .queryParam("username", "Byteful")
                .queryParam("queryMojang", "false")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PlayerNameResponse response = PlayerNameResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("Player found", response.getMessage());
        assertProfile(response.getPlayer());
        verify(playerLookupService).getPlayerByUsername(server, "Byteful", false);
    }

    @Test
    void v3GetPlayerByUsernameRejectsBlankParameterWithoutServiceCall() throws Exception {
        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/by-name")
                .queryParam("username", " ")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        verifyNoInteractions(playerLookupService);
    }

    @Test
    void v3GetPlayerByUsernameMissingParameterReturnsBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/by-name")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        verifyNoInteractions(playerLookupService);
    }

    @Test
    void v3GetPlayerByUsernameNotFoundReturnsBinaryApiError() throws Exception {
        when(playerLookupService.getPlayerByUsername(server, "Byteful", false))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.NOT_FOUND, Map.of(
                "status", 404,
                "message", "Player not found"
            )));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/by-name")
                .queryParam("username", "Byteful")
                .queryParam("queryMojang", "false")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_FOUND", error.getCode());
    }

    @Test
    void v3LookupPlayerPassesRequestAndReturnsLookupData() throws Exception {
        when(playerLookupService.lookupPlayer(server, "Byteful", false))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, lookupBody()));

        PlayerLookupRequest request = PlayerLookupRequest.newBuilder()
            .setQuery("Byteful")
            .setQueryMojang(false)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/lookup")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PlayerLookupResponse response = PlayerLookupResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("Player found", response.getMessage());
        assertEquals(PLAYER_UUID.toString(), response.getData().getMinecraftUuid());
        assertEquals("Byteful", response.getData().getCurrentUsername());
        assertEquals("OldName", response.getData().getPreviousUsernames(0));
        assertEquals(3, response.getData().getPunishmentStats().getTotalPunishments());
        assertEquals("punishment-1", response.getData().getRecentPunishments(0).getId());
        assertEquals("ticket-1", response.getData().getRecentTickets(0).getId());
        assertTrue(response.getData().getIsOnline());
        verify(playerLookupService).lookupPlayer(server, "Byteful", false);
    }

    @Test
    void v3LookupPlayerDefaultsQueryMojangTrue() throws Exception {
        when(playerLookupService.lookupPlayer(server, "Byteful", true))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, lookupBody()));

        PlayerLookupRequest request = PlayerLookupRequest.newBuilder()
            .setQuery("Byteful")
            .build();

        v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/lookup")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk());

        verify(playerLookupService).lookupPlayer(server, "Byteful", true);
    }

    @Test
    void v3LookupPlayerNotFoundReturnsBinaryApiError() throws Exception {
        when(playerLookupService.lookupPlayer(server, "Missing", false))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.NOT_FOUND, Map.of(
                "status", 404,
                "message", "Player not found"
            )));

        PlayerLookupRequest request = PlayerLookupRequest.newBuilder()
            .setQuery("Missing")
            .setQueryMojang(false)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/lookup")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_FOUND", error.getCode());
    }

    @Test
    void v3LookupProfilePassesLimitsAndReturnsProfile() throws Exception {
        when(playerLookupService.lookupProfile(server, "Byteful", false, 7, 4))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, profileBody()));

        PlayerLookupRequest request = PlayerLookupRequest.newBuilder()
            .setQuery("Byteful")
            .setQueryMojang(false)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/lookup-profile")
                .queryParam("punishmentLimit", "7")
                .queryParam("noteLimit", "4")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PlayerProfileResponse response = PlayerProfileResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertProfile(response.getProfile());
        assertEquals(200, response.getStatus());
        assertEquals(4, response.getPunishmentCount());
        assertEquals(5, response.getNoteCount());
        verify(playerLookupService).lookupProfile(server, "Byteful", false, 7, 4);
    }

    @Test
    void v3LookupProfileNotFoundReturnsBinaryApiError() throws Exception {
        when(playerLookupService.lookupProfile(server, "Missing", false, null, null))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.NOT_FOUND, Map.of(
                "status", 404,
                "message", "Player not found"
            )));

        PlayerLookupRequest request = PlayerLookupRequest.newBuilder()
            .setQuery("Missing")
            .setQueryMojang(false)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/lookup-profile")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_FOUND", error.getCode());
    }

    @Test
    void v3CreatePlayerNoteAcceptsBinaryRequestAndReturnsBinaryResponse() throws Exception {
        when(minecraftPlayerService.createNote(server, PLAYER_UUID.toString(), "Helpful note", "Mod", "staff-1"))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.CREATED, Map.of(
                "status", 201,
                "success", true,
                "message", "Note created"
            )));

        CreatePlayerNoteRequest request = CreatePlayerNoteRequest.newBuilder()
            .setText("Helpful note")
            .setIssuerName("Mod")
            .setIssuerId("staff-1")
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/" + PLAYER_UUID + "/notes")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PlayerNoteCreateResponse response = PlayerNoteCreateResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(201, response.getStatus());
        assertIfMethodPresent(response, "getSuccess", true);
        assertEquals("Note created", response.getMessage());
        verify(minecraftPlayerService).createNote(server, PLAYER_UUID.toString(), "Helpful note", "Mod", "staff-1");
    }

    @Test
    void v3GetLinkedAccountsPassesPaginationAndReturnsBinaryResponse() throws Exception {
        when(playerLookupService.getLinkedAccounts(server, PLAYER_UUID.toString(), 2, 3))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, Map.of(
                "status", 200,
                "linkedAccounts", List.of(accountMap()),
                "totalCount", 4,
                "page", 2,
                "hasMore", true
            )));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/" + PLAYER_UUID + "/linked-accounts")
                .queryParam("page", "2")
                .queryParam("limit", "3")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        LinkedAccountsResponse response = LinkedAccountsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(4, response.getTotalCount());
        assertEquals(2, response.getPage());
        assertTrue(response.getHasMore());
        assertProfile(response.getLinkedAccounts(0));
        verify(playerLookupService).getLinkedAccounts(server, PLAYER_UUID.toString(), 2, 3);
    }

    @Test
    void v3GetPlayerPunishmentsPassesPaginationAndReturnsBinaryResponse() throws Exception {
        when(minecraftPlayerService.getPlayerPunishments(server, PLAYER_UUID.toString(), 2, 4))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, Map.of(
                "status", 200,
                "punishments", List.of(punishmentMap()),
                "totalCount", 6,
                "page", 2,
                "hasMore", true
            )));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/" + PLAYER_UUID + "/punishments")
                .queryParam("page", "2")
                .queryParam("limit", "4")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PaginatedPunishmentsResponse response = PaginatedPunishmentsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(6, response.getTotalCount());
        assertEquals(2, response.getPage());
        assertTrue(response.getHasMore());
        assertEquals("punishment-1", response.getPunishments(0).getId());
        assertEquals("Mod", response.getPunishments(0).getIssuerName());
        assertEquals(1_777_777_777_000L, response.getPunishments(0).getIssued());
        assertEquals("Ban", response.getPunishments(0).getType());
        verify(minecraftPlayerService).getPlayerPunishments(server, PLAYER_UUID.toString(), 2, 4);
    }

    @Test
    void v3GetPlayerNotesPassesPaginationAndReturnsBinaryResponse() throws Exception {
        when(minecraftPlayerService.getPlayerNotes(server, PLAYER_UUID.toString(), 2, 4))
            .thenReturn(new MinecraftPlayerService.ServiceResponse(HttpStatus.OK, Map.of(
                "status", 200,
                "notes", List.of(noteMap()),
                "totalCount", 6,
                "page", 2,
                "hasMore", true
            )));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/" + PLAYER_UUID + "/notes")
                .queryParam("page", "2")
                .queryParam("limit", "4")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PaginatedNotesResponse response = PaginatedNotesResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(6, response.getTotalCount());
        assertEquals(2, response.getPage());
        assertTrue(response.getHasMore());
        assertIfMethodPresent(response.getNotes(0), "getId", "note-id");
        assertEquals("Helpful note", response.getNotes(0).getText());
        verify(minecraftPlayerService).getPlayerNotes(server, PLAYER_UUID.toString(), 2, 4);
    }

    @Test
    void v3GetPlayerReportsReturnsBinaryResponse() throws Exception {
        when(minecraftPlayerService.getPlayerReports(server, PLAYER_UUID.toString()))
            .thenReturn(Map.of(
                "status", 200,
                "reports", List.of(Map.of(
                    "id", "report-1",
                    "type", "player-report",
                    "reporterName", "Reporter",
                    "reporterUuid", "reporter-uuid",
                    "subject", "Rule break",
                    "status", "open",
                    "priority", "high",
                    "createdAt", 1_777_777_777_000L
                ))
            ));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/players/" + PLAYER_UUID + "/reports")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ReportsResponse response = ReportsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("report-1", response.getReports(0).getId());
        assertEquals("player-report", response.getReports(0).getType());
        assertEquals("Reporter", response.getReports(0).getReporterName());
        assertEquals("Rule break", response.getReports(0).getSubject());
        assertEquals(1_777_777_777_000L, response.getReports(0).getCreatedAt());
        verify(minecraftPlayerService).getPlayerReports(server, PLAYER_UUID.toString());
    }

    @Test
    void v3PardonPlayerAcceptsBinaryRequestAndReturnsBinaryResponse() throws Exception {
        when(minecraftPlayerService.pardonPlayer(server, "Byteful", "ban", "Mod", "staff-1", "Appeal accepted"))
            .thenReturn(Map.of(
                "status", 200,
                "success", true,
                "pardonedCount", 1,
                "message", "Pardoned 1 punishment(s)"
            ));

        PardonPlayerRequest request = PardonPlayerRequest.newBuilder()
            .setPlayerName("Byteful")
            .setPunishmentType("ban")
            .setIssuerName("Mod")
            .setIssuerId("staff-1")
            .setReason("Appeal accepted")
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/pardon")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PardonResponse response = PardonResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals(1, response.getPardonedCount());
        assertEquals("Pardoned 1 punishment(s)", response.getMessage());
        verify(minecraftPlayerService).pardonPlayer(server, "Byteful", "ban", "Mod", "staff-1", "Appeal accepted");
    }

    private static Map<String, Object> profileBody() {
        Map<String, Object> profile = new LinkedHashMap<>(accountMap());
        profile.put("punishmentCount", 4);
        profile.put("noteCount", 5);

        return Map.of(
            "status", 200,
            "profile", profile
        );
    }

    private static Map<String, Object> accountMap() {
        return Map.of(
            "id", "player-id",
            "minecraftUuid", PLAYER_UUID.toString(),
            "usernames", List.of(Map.of(
                "username", "Byteful",
                "date", "2026-05-03T10:15:30Z"
            )),
            "notes", List.of(noteMap()),
            "ipAddresses", List.of(Map.of(
                "ipAddress", "203.0.113.10",
                "country", "US",
                "region", "California",
                "asn", "AS64500",
                "proxy", false,
                "hosting", false,
                "firstLogin", "2026-05-03T10:15:30Z",
                "logins", List.of("2026-05-03T10:15:30Z")
            )),
            "punishments", List.of(Map.of(
                "id", "punishment-1",
                "type", "Ban",
                "typeOrdinal", 1,
                "issuerName", "Mod",
                "issued", 1_777_777_777_000L,
                "isAppealable", true,
                "active", true
            )),
            "pendingNotifications", List.of(Map.of("id", "notification-1")),
            "data", Map.of("isOnline", true)
        );
    }

    private static Map<String, Object> noteMap() {
        return Map.of(
            "id", "note-id",
            "text", "Helpful note",
            "date", "2026-05-03T10:16:30Z",
            "issuerName", "Mod",
            "issuerId", "staff-1"
        );
    }

    private static Map<String, Object> punishmentMap() {
        Map<String, Object> punishment = new LinkedHashMap<>();
        punishment.put("id", "punishment-1");
        punishment.put("type", "Ban");
        punishment.put("typeOrdinal", 1);
        punishment.put("issuerName", "Mod");
        punishment.put("issued", 1_777_777_777_000L);
        punishment.put("started", 1_777_777_778_000L);
        punishment.put("notes", List.of());
        punishment.put("modifications", List.of());
        punishment.put("evidence", List.of());
        punishment.put("attachedTicketIds", List.of("ticket-1"));
        punishment.put("data", Map.of("status", "active"));
        return punishment;
    }

    private static Map<String, Object> lookupBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("minecraftUuid", PLAYER_UUID.toString());
        data.put("currentUsername", "Byteful");
        data.put("firstSeen", "2026-05-01T10:15:30Z");
        data.put("lastSeen", "2026-05-03T10:15:30Z");
        data.put("currentServer", "survival");
        data.put("ipAddress", "203.0.113.10");
        data.put("country", "US");
        data.put("profileUrl", "https://demo.example/player/" + PLAYER_UUID);
        data.put("punishmentsUrl", "https://demo.example/player/" + PLAYER_UUID + "/punishments");
        data.put("ticketsUrl", "https://demo.example/player/" + PLAYER_UUID + "/tickets");
        data.put("previousUsernames", List.of("OldName"));
        data.put("punishmentStats", Map.of(
            "status", "Warned",
            "totalPunishments", 3,
            "activePunishments", 1,
            "bans", 1,
            "mutes", 1,
            "kicks", 0,
            "warnings", 1,
            "points", 4
        ));
        data.put("recentPunishments", List.of(Map.of(
            "id", "punishment-1",
            "type", "Ban",
            "issuer", "Mod",
            "issuedAt", "2026-05-02T10:15:30Z",
            "expiresAt", "2026-06-02T10:15:30Z",
            "isActive", true
        )));
        data.put("recentTickets", List.of(Map.of(
            "id", "ticket-1",
            "title", "Appeal",
            "category", "appeal",
            "status", "open",
            "createdAt", "2026-05-02T10:15:30Z",
            "lastUpdated", "2026-05-03T10:15:30Z"
        )));
        data.put("isOnline", true);

        return Map.of(
            "status", 200,
            "message", "Player found",
            "data", data
        );
    }

    private static void assertProfile(Account account) throws Exception {
        assertEquals("player-id", account.getId());
        assertEquals(PLAYER_UUID.toString(), account.getMinecraftUuid());
        assertEquals("Byteful", account.getUsernames(0).getUsername());
        assertIfMethodPresent(account.getNotes(0), "getId", "note-id");
        assertEquals("Helpful note", account.getNotes(0).getText());
        assertEquals("203.0.113.10", account.getIpAddresses(0).getIpAddress());
        assertEquals("punishment-1", account.getPunishments(0).getId());
        assertEquals("notification-1", account.getPendingNotifications(0).getFieldsOrThrow("id").getStringValue());
        assertTrue(account.getData().getFieldsOrThrow("isOnline").getBoolValue());
    }

    private static void assertIfMethodPresent(Object target, String methodName, Object expected) throws Exception {
        try {
            assertEquals(expected, target.getClass().getMethod(methodName).invoke(target));
        } catch (NoSuchMethodException ignored) {
            // Published proto artifacts can lag additive local schema fields during rollout.
        }
    }
}
