package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static gg.modl.backend.minecraft.BackendPunishmentPreviewFactory.previewBuilder;
import static gg.modl.backend.minecraft.BackendPunishmentPreviewFactory.previewError;
import static gg.modl.backend.minecraft.BackendPunishmentPreviewFactory.severityPreview;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Empty;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import gg.modl.backend.infrastructure.exception.ErrorResponseDTO;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.player.controller.v1.MinecraftPunishmentController;
import gg.modl.backend.player.controller.v3.MinecraftPunishmentV3Controller;
import gg.modl.backend.player.dto.response.PunishmentPreviewView;
import gg.modl.backend.player.dto.response.PunishmentSeverityPreviewView;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.player.service.PunishmentQueryService;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.AddPunishmentEvidenceRequest;
import gg.modl.proto.modl.v1.AddPunishmentEvidenceResponse;
import gg.modl.proto.modl.v1.AddPunishmentNoteRequest;
import gg.modl.proto.modl.v1.AddPunishmentNoteResponse;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.ChangePunishmentDurationRequest;
import gg.modl.proto.modl.v1.ChangePunishmentDurationResponse;
import gg.modl.proto.modl.v1.CreatePunishmentRequest;
import gg.modl.proto.modl.v1.CreateEvidenceUploadTokenRequest;
import gg.modl.proto.modl.v1.EvidenceUploadTokenResponse;
import gg.modl.proto.modl.v1.ModifyPunishmentTicketsRequest;
import gg.modl.proto.modl.v1.ModifyPunishmentTicketsResponse;
import gg.modl.proto.modl.v1.PardonPunishmentRequest;
import gg.modl.proto.modl.v1.PardonResponse;
import gg.modl.proto.modl.v1.PunishmentAcknowledgeRequest;
import gg.modl.proto.modl.v1.PunishmentAcknowledgeResponse;
import gg.modl.proto.modl.v1.PunishmentCreateResponse;
import gg.modl.proto.modl.v1.PunishmentDetailResponse;
import gg.modl.proto.modl.v1.PunishmentPreviewResponse;
import gg.modl.proto.modl.v1.RecentPunishmentsResponse;
import gg.modl.proto.modl.v1.StatWipeAcknowledgeRequest;
import gg.modl.proto.modl.v1.StatWipeAcknowledgeResponse;
import gg.modl.proto.modl.v1.TogglePunishmentOptionRequest;
import gg.modl.proto.modl.v1.TogglePunishmentOptionResponse;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftPunishmentV3ControllerTest {
    private static final String PLAYER_UUID = "11111111-2222-3333-4444-555555555555";

    @Mock private PunishmentLifecycleService punishmentLifecycleService;
    @Mock private PunishmentMutationService punishmentMutationService;
    @Mock private PunishmentEvidenceService punishmentEvidenceService;
    @Mock private PunishmentQueryService punishmentQueryService;
    private MockMvc v3MockMvc;
    private MockMvc v1MockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        v3MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftPunishmentV3Controller(
                punishmentLifecycleService,
                punishmentMutationService,
                punishmentEvidenceService,
                punishmentQueryService
            ))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(post("/").requestAttr(RequestAttribute.SERVER, server))
            .build();

        v1MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftPunishmentController(
                punishmentQueryService,
                punishmentLifecycleService,
                punishmentEvidenceService,
                punishmentMutationService
            ))
            .setControllerAdvice(new GlobalExceptionHandler())
            .defaultRequest(post("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3PreviewSuccessReturnsBinaryResponseMappedFromDtoAndCallsServiceWithQueryParams() throws Exception {
        PunishmentPreviewView dto =
            previewBuilder()
                .status(200)
                .success(true)
                .message("Preview calculated")
                .socialStatus("medium")
                .gameplayStatus("low")
                .socialPoints(12)
                .gameplayPoints(3)
                .offenderStatus("high")
                .category("chat")
                .singleSeverityPunishment(false)
                .permanentUntilUsernameChange(true)
                .permanentUntilSkinChange(false)
                .canBeAltBlocking(true)
                .canBeStatWiping(false)
                .lenient(severityPreview("lenient", 1, 3_600_000L, "1h", "mute", false, "medium", "low", 13, 3))
                .regular(severityPreview("regular", 2, 7_200_000L, "2h", "mute", false, "high", "low", 14, 3))
                .aggravated(severityPreview("aggravated", 3, 0L, "Permanent", "ban", true, "high", "medium", 15, 4))
                .singleSeverity(severityPreview("single", 4, 86_400_000L, "1d", "ban", false, "critical", "high", 16, 5))
                .build();
        when(punishmentQueryService.previewPunishment(server, PLAYER_UUID, 7)).thenReturn(dto);

        MvcResult result = performV3Preview(PLAYER_UUID, 7)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentPreviewResponse response =
            PunishmentPreviewResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("Preview calculated", response.getMessage());
        assertEquals("medium", response.getSocialStatus());
        assertEquals("low", response.getGameplayStatus());
        assertEquals("high", response.getOffenderStatus());
        assertEquals("chat", response.getCategory());
        assertEquals(12, response.getSocialPoints());
        assertEquals(3, response.getGameplayPoints());
        assertFalse(response.getSingleSeverityPunishment());
        assertTrue(response.getPermanentUntilUsernameChange());
        assertFalse(response.getPermanentUntilSkinChange());
        assertTrue(response.getCanBeAltBlocking());
        assertFalse(response.getCanBeStatWiping());
        assertSeverityPreview(response.getLenient(), "lenient", 1, 3_600_000L, "1h", "mute", false, "medium", "low", 13, 3);
        assertSeverityPreview(response.getRegular(), "regular", 2, 7_200_000L, "2h", "mute", false, "high", "low", 14, 3);
        assertSeverityPreview(response.getAggravated(), "aggravated", 3, 0L, "Permanent", "ban", true, "high", "medium", 15, 4);
        assertSeverityPreview(response.getSingleSeverity(), "single", 4, 86_400_000L, "1d", "ban", false, "critical", "high", 16, 5);

        verify(punishmentQueryService).previewPunishment(server, PLAYER_UUID, 7);
        verifyNoMoreInteractions(punishmentQueryService);
    }

    @Test
    void v3PreviewErrorDtoReturnsBinaryOkWithEmbeddedFailureAndNoSeverityPreviews() throws Exception {
        PunishmentPreviewView dto = previewError("Player not found");
        when(punishmentQueryService.previewPunishment(server, PLAYER_UUID, 7)).thenReturn(dto);

        MvcResult result = performV3Preview(PLAYER_UUID, 7)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentPreviewResponse response =
            PunishmentPreviewResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Player not found", response.getMessage());
        assertFalse(response.hasLenient());
        assertFalse(response.hasRegular());
        assertFalse(response.hasAggravated());
        assertFalse(response.hasSingleSeverity());
    }

    @Test
    void v3PreviewRejectsJsonAcceptWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/preview")
                .queryParam("playerUuid", PLAYER_UUID)
                .queryParam("typeOrdinal", "7")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v1PreviewStillReturnsJsonResponse() throws Exception {
        PunishmentPreviewView dto =
            previewBuilder()
                .status(200)
                .success(true)
                .message("Preview calculated")
                .socialStatus("low")
                .gameplayStatus("medium")
                .socialPoints(1)
                .gameplayPoints(2)
                .offenderStatus("low")
                .category("chat")
                .build();
        when(punishmentQueryService.previewPunishment(server, PLAYER_UUID, 7)).thenReturn(dto);

        MvcResult result = v1MockMvc.perform(get(RESTMappingV1.MINECRAFT_PUNISHMENTS + "/preview")
                .queryParam("playerUuid", PLAYER_UUID)
                .queryParam("typeOrdinal", "7")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper()
            .readTree(result.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("status").asInt());
        assertTrue(json.get("success").asBoolean());
        assertEquals("Preview calculated", json.get("message").asText());
        assertEquals("chat", json.get("category").asText());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
    }

    @Test
    void v3DetailSuccessReturnsBinaryResponseMappedFromLegacyPunishmentMap() throws Exception {
        Map<String, Object> data = nestedStructMap();

        Map<String, Object> modification = new LinkedHashMap<>();
        modification.put("id", "mod-1");
        modification.put("date", new Date(1_700_000_030_000L));
        modification.put("type", "DURATION_CHANGE");
        modification.put("data", Map.of("duration", "temporary"));

        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", "note-1");
        note.put("date", new Date(1_700_000_040_000L));
        note.put("text", "Initial note");
        note.put("metadata", List.of("internal", Map.of("level", 2)));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("type", "FILE");
        evidence.put("url", "https://example.com/evidence.png");
        evidence.put("uploadedAt", new Date(1_700_000_050_000L));
        evidence.put("fileSize", 2048L);

        Map<String, Object> punishment = new LinkedHashMap<>();
        punishment.put("playerName", "PlayerOne");
        punishment.put("playerUuid", PLAYER_UUID);
        punishment.put("id", "punishment-1");
        punishment.put("issuerName", "Mod");
        punishment.put("issued", new Date(1_700_000_000_000L));
        punishment.put("started", new Date(1_700_000_010_000L));
        punishment.put("type", "Mute");
        punishment.put("typeOrdinal", 7);
        punishment.put("modifications", List.of(modification));
        punishment.put("notes", List.of(note));
        punishment.put("evidence", List.of(evidence));
        punishment.put("attachedTicketIds", List.of("ticket-1", "ticket-2"));
        punishment.put("data", data);
        when(punishmentQueryService.getMinecraftPunishmentById(server, "punishment-1"))
            .thenReturn(Optional.of(punishment));

        MvcResult result = performV3Detail("punishment-1")
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentDetailResponse response =
            PunishmentDetailResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.hasPunishment());
        PunishmentDetailResponse.PunishmentDetailEntry detail = response.getPunishment();
        assertEquals("PlayerOne", detail.getPlayerName());
        assertEquals(PLAYER_UUID, detail.getPlayerUuid());
        assertEquals("punishment-1", detail.getId());
        assertEquals("Mod", detail.getIssuerName());
        assertEquals("2023-11-14T22:13:20Z", detail.getIssued());
        assertEquals("2023-11-14T22:13:30Z", detail.getStarted());
        assertEquals("Mute", detail.getType());
        assertEquals(7, detail.getTypeOrdinal());
        assertEquals(List.of("ticket-1", "ticket-2"), detail.getAttachedTicketIdsList());
        assertNestedStructMap(detail.getData());

        assertEquals(1, detail.getModificationsCount());
        assertStructField(detail.getModifications(0), "id", "mod-1");
        assertStructField(detail.getModifications(0), "type", "DURATION_CHANGE");
        assertStructField(detail.getModifications(0), "date", "2023-11-14T22:13:50Z");
        assertEquals("temporary", detail.getModifications(0).getFieldsOrThrow("data")
            .getStructValue().getFieldsOrThrow("duration").getStringValue());

        assertEquals(1, detail.getNotesCount());
        assertStructField(detail.getNotes(0), "id", "note-1");
        assertStructField(detail.getNotes(0), "date", "2023-11-14T22:14:00Z");
        assertStructField(detail.getNotes(0), "text", "Initial note");
        assertEquals("internal", detail.getNotes(0).getFieldsOrThrow("metadata")
            .getListValue().getValues(0).getStringValue());
        assertEquals(2.0, detail.getNotes(0).getFieldsOrThrow("metadata")
            .getListValue().getValues(1).getStructValue().getFieldsOrThrow("level").getNumberValue());

        assertEquals(1, detail.getEvidenceCount());
        assertStructField(detail.getEvidence(0), "type", "FILE");
        assertStructField(detail.getEvidence(0), "url", "https://example.com/evidence.png");
        assertStructField(detail.getEvidence(0), "uploadedAt", "2023-11-14T22:14:10Z");
        assertStructNumberField(detail.getEvidence(0), "fileSize", 2048.0);

        verify(punishmentQueryService).getMinecraftPunishmentById(server, "punishment-1");
        verifyNoMoreInteractions(punishmentQueryService);
    }

    @Test
    void v3DetailNotFoundReturnsBinaryApiError() throws Exception {
        when(punishmentQueryService.getMinecraftPunishmentById(server, "missing-punishment"))
            .thenReturn(Optional.empty());

        MvcResult result = performV3Detail("missing-punishment")
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_FOUND", error.getCode());
        assertEquals("Punishment not found", error.getMessage());
        verify(punishmentQueryService).getMinecraftPunishmentById(server, "missing-punishment");
        verifyNoMoreInteractions(punishmentQueryService);
    }

    @Test
    void v3DetailRejectsJsonAcceptWithBinaryApiErrorAndSkipsService() throws Exception {
        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/punishment-1")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v1DetailStillReturnsJsonMapShape() throws Exception {
        Map<String, Object> punishment = new LinkedHashMap<>();
        punishment.put("id", "punishment-1");
        punishment.put("playerUuid", PLAYER_UUID);
        punishment.put("playerName", "PlayerOne");
        when(punishmentQueryService.getMinecraftPunishmentById(server, "punishment-1"))
            .thenReturn(Optional.of(punishment));

        MvcResult result = v1MockMvc.perform(get(RESTMappingV1.MINECRAFT_PUNISHMENTS + "/punishment-1")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper()
            .readTree(result.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("status").asInt());
        assertEquals("punishment-1", json.get("punishment").get("id").asText());
        assertEquals(PLAYER_UUID, json.get("punishment").get("playerUuid").asText());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));

        verify(punishmentQueryService).getMinecraftPunishmentById(server, "punishment-1");
        verifyNoMoreInteractions(punishmentQueryService);
    }

    @Test
    void v3RecentSuccessReturnsBinaryResponseMappedFromLegacyPunishmentMap() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("string", "value");
        data.put("number", 12.5);
        data.put("boolean", true);
        data.put("nullish", null);
        data.put("list", List.of("first", 2, false));
        data.put("nested", Map.of("child", "inside"));

        Map<String, Object> modificationData = Map.of("duration", "temporary");
        Map<String, Object> modification = new LinkedHashMap<>();
        modification.put("id", "mod-1");
        modification.put("type", "DURATION_CHANGE");
        modification.put("date", new Date(1_700_000_030_000L));
        modification.put("issuerName", "Senior Mod");
        modification.put("effectiveDuration", 3_600_000L);
        modification.put("data", modificationData);

        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", "note-1");
        note.put("text", "Initial note");
        note.put("issuerName", "Note Mod");
        note.put("date", new Date(1_700_000_040_000L));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("text", "Screenshot evidence");
        evidence.put("url", "https://example.com/evidence.png");
        evidence.put("type", "FILE");
        evidence.put("uploadedBy", "Evidence Mod");
        evidence.put("uploadedAt", new Date(1_700_000_050_000L));
        evidence.put("fileName", "evidence.png");
        evidence.put("fileType", "image/png");
        evidence.put("fileSize", 2048L);

        Map<String, Object> punishment = new LinkedHashMap<>();
        punishment.put("playerName", "PlayerOne");
        punishment.put("playerUuid", PLAYER_UUID);
        punishment.put("id", "punishment-1");
        punishment.put("issuerName", "Mod");
        punishment.put("issued", new Date(1_700_000_000_000L));
        punishment.put("started", new Date(1_700_000_010_000L));
        punishment.put("type", "Mute");
        punishment.put("typeOrdinal", 7);
        punishment.put("modifications", List.of(modification));
        punishment.put("notes", List.of(note));
        punishment.put("evidence", List.of(evidence));
        punishment.put("attachedTicketIds", List.of("ticket-1", "ticket-2"));
        punishment.put("data", data);
        when(punishmentQueryService.getRecentPunishments(server, 12)).thenReturn(List.of(punishment));

        MvcResult result = performV3Recent("12")
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        RecentPunishmentsResponse response =
            RecentPunishmentsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(1, response.getPunishmentsCount());
        RecentPunishmentsResponse.RecentPunishment recent = response.getPunishments(0);
        assertEquals("PlayerOne", recent.getPlayerName());
        assertEquals(PLAYER_UUID, recent.getPlayerUuid());
        assertEquals("punishment-1", recent.getId());
        assertEquals("Mod", recent.getIssuerName());
        assertEquals(1_700_000_000_000L, recent.getIssued());
        assertTrue(recent.hasStarted());
        assertEquals(1_700_000_010_000L, recent.getStarted());
        assertEquals("Mute", recent.getType());
        assertTrue(recent.hasTypeOrdinal());
        assertEquals(7, recent.getTypeOrdinal());
        assertEquals(List.of("ticket-1", "ticket-2"), recent.getAttachedTicketIdsList());
        assertStructField(recent.getData(), "string", "value");
        assertStructNumberField(recent.getData(), "number", 12.5);
        assertStructField(recent.getData(), "boolean", true);
        assertEquals(NullValue.NULL_VALUE, recent.getData().getFieldsOrThrow("nullish").getNullValue());
        assertEquals("first", recent.getData().getFieldsOrThrow("list").getListValue().getValues(0).getStringValue());
        assertEquals(2.0, recent.getData().getFieldsOrThrow("list").getListValue().getValues(1).getNumberValue());
        assertFalse(recent.getData().getFieldsOrThrow("list").getListValue().getValues(2).getBoolValue());
        assertEquals("inside", recent.getData().getFieldsOrThrow("nested")
            .getStructValue().getFieldsOrThrow("child").getStringValue());

        assertEquals(1, recent.getModificationsCount());
        assertEquals("mod-1", recent.getModifications(0).getId());
        assertEquals("DURATION_CHANGE", recent.getModifications(0).getType());
        assertEquals(1_700_000_030_000L, recent.getModifications(0).getDate());
        assertTrue(recent.getModifications(0).hasIssuerName());
        assertEquals("Senior Mod", recent.getModifications(0).getIssuerName());
        assertTrue(recent.getModifications(0).hasEffectiveDuration());
        assertEquals(3_600_000L, recent.getModifications(0).getEffectiveDuration());
        assertFalse(recent.getModifications(0).hasIssuerId());
        assertFalse(recent.getModifications(0).hasAppealTicketId());
        assertStructField(recent.getModifications(0).getData(), "duration", "temporary");

        assertEquals(1, recent.getNotesCount());
        assertEquals("note-1", recent.getNotes(0).getId());
        assertEquals("Initial note", recent.getNotes(0).getText());
        assertEquals("Note Mod", recent.getNotes(0).getIssuerName());
        assertEquals(1_700_000_040_000L, recent.getNotes(0).getDate());
        assertFalse(recent.getNotes(0).hasIssuerId());

        assertEquals(1, recent.getEvidenceCount());
        assertEquals("Screenshot evidence", recent.getEvidence(0).getText());
        assertEquals("https://example.com/evidence.png", recent.getEvidence(0).getUrl());
        assertEquals("FILE", recent.getEvidence(0).getType());
        assertEquals("Evidence Mod", recent.getEvidence(0).getUploadedBy());
        assertEquals(1_700_000_050_000L, recent.getEvidence(0).getUploadedAt());
        assertEquals("evidence.png", recent.getEvidence(0).getFileName());
        assertEquals("image/png", recent.getEvidence(0).getFileType());
        assertEquals(2048L, recent.getEvidence(0).getFileSize());
        assertFalse(recent.getEvidence(0).hasUploadedById());

        verify(punishmentQueryService).getRecentPunishments(server, 12);
        verifyNoMoreInteractions(punishmentQueryService);
    }

    @Test
    void v3RecentEmptyListReturnsBinaryStatusAndNoPunishments() throws Exception {
        when(punishmentQueryService.getRecentPunishments(server, 48)).thenReturn(List.of());

        MvcResult result = performV3Recent()
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        RecentPunishmentsResponse response =
            RecentPunishmentsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(0, response.getPunishmentsCount());
        verify(punishmentQueryService).getRecentPunishments(server, 48);
        verifyNoMoreInteractions(punishmentQueryService);
    }

    @Test
    void v3RecentDefaultsHoursTo48WhenQueryParamIsOmitted() throws Exception {
        when(punishmentQueryService.getRecentPunishments(server, 48)).thenReturn(List.of());

        performV3Recent()
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF));

        verify(punishmentQueryService).getRecentPunishments(server, 48);
        verifyNoMoreInteractions(punishmentQueryService);
    }

    @Test
    void v3RecentPassesExplicitHoursToService() throws Exception {
        when(punishmentQueryService.getRecentPunishments(server, 72)).thenReturn(List.of());

        performV3Recent("72")
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF));

        verify(punishmentQueryService).getRecentPunishments(server, 72);
        verifyNoMoreInteractions(punishmentQueryService);
    }

    @Test
    void v3RecentRejectsHoursBelowRangeWithBinaryApiErrorAndSkipsService() throws Exception {
        MvcResult result = performV3Recent("0")
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v3RecentRejectsHoursAboveRangeWithBinaryApiErrorAndSkipsService() throws Exception {
        MvcResult result = performV3Recent("8761")
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v3RecentRejectsMalformedHoursWithBinaryApiErrorAndSkipsService() throws Exception {
        MvcResult result = performV3Recent("abc")
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertEquals("Invalid value for parameter: hours", error.getMessage());
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v3RecentRejectsJsonAcceptWithBinaryApiErrorAndSkipsService() throws Exception {
        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/recent")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v1RecentStillReturnsJsonMapShape() throws Exception {
        Map<String, Object> punishment = new LinkedHashMap<>();
        punishment.put("playerName", "PlayerOne");
        punishment.put("playerUuid", PLAYER_UUID);
        punishment.put("id", "punishment-1");
        punishment.put("issued", new Date(1_700_000_000_000L));
        when(punishmentQueryService.getRecentPunishments(server, 48)).thenReturn(List.of(punishment));

        MvcResult result = v1MockMvc.perform(get(RESTMappingV1.MINECRAFT_PUNISHMENTS + "/recent")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper()
            .readTree(result.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("status").asInt());
        assertEquals(1, json.get("punishments").size());
        assertEquals("punishment-1", json.get("punishments").get(0).get("id").asText());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verify(punishmentQueryService).getRecentPunishments(server, 48);
        verifyNoMoreInteractions(punishmentQueryService);
    }

    @Test
    void v1RecentMalformedHoursStillReturnsJsonErrorAndSkipsService() throws Exception {
        MvcResult result = v1MockMvc.perform(get(RESTMappingV1.MINECRAFT_PUNISHMENTS + "/recent")
                .queryParam("hours", "abc")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid value for parameter: hours", error.error());
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v3CreateDynamicSuccessConvertsAllFieldsAndDataStructAndReturnsBinaryResponse() throws Exception {
        when(punishmentLifecycleService.createMinecraftPunishment(same(server), any()))
            .thenReturn("punishment-1");

        CreatePunishmentRequest request = validCreatePunishmentRequest().toBuilder()
            .setData(Struct.newBuilder()
                .putFields("flag", Value.newBuilder().setBoolValue(true).build())
                .putFields("score", Value.newBuilder().setNumberValue(12.5).build())
                .putFields("text", Value.newBuilder().setStringValue("value").build())
                .putFields("nullish", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
                .putFields("list", Value.newBuilder().setListValue(ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("first").build())
                    .addValues(Value.newBuilder().setNumberValue(2).build())
                    .addValues(Value.newBuilder().setBoolValue(false).build())
                    .build()).build())
                .putFields("nested", Value.newBuilder().setStructValue(Struct.newBuilder()
                    .putFields("child", Value.newBuilder().setStringValue("inside").build())
                    .build()).build())
                .build())
            .build();

        MvcResult result = performV3CreateDynamic(request)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentCreateResponse response =
            PunishmentCreateResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("Punishment created", response.getMessage());
        assertEquals("punishment-1", response.getPunishmentId());

        ArgumentCaptor<MinecraftPunishmentController.MinecraftCreatePunishmentRequest> requestCaptor =
            ArgumentCaptor.forClass(MinecraftPunishmentController.MinecraftCreatePunishmentRequest.class);
        verify(punishmentLifecycleService).createMinecraftPunishment(same(server), requestCaptor.capture());
        MinecraftPunishmentController.MinecraftCreatePunishmentRequest legacyRequest = requestCaptor.getValue();
        assertEquals(PLAYER_UUID, legacyRequest.targetUuid());
        assertEquals("Mod", legacyRequest.issuerName());
        assertEquals("issuer-1", legacyRequest.issuerId());
        assertEquals(7, legacyRequest.typeOrdinal());
        assertEquals("Rule violation", legacyRequest.reason());
        assertEquals(3600L, legacyRequest.duration());
        assertEquals(List.of("note-1", "note-2"), legacyRequest.notes());
        assertEquals(List.of("ticket-1", "ticket-2"), legacyRequest.attachedTicketIds());
        assertEquals("regular", legacyRequest.severity());
        assertEquals("Queued", legacyRequest.status());
        assertEquals(true, legacyRequest.data().get("flag"));
        assertEquals(12.5, legacyRequest.data().get("score"));
        assertEquals("value", legacyRequest.data().get("text"));
        assertNull(legacyRequest.data().get("nullish"));
        assertEquals(List.of("first", 2.0, false), legacyRequest.data().get("list"));
        assertEquals(Map.of("child", "inside"), legacyRequest.data().get("nested"));
    }

    @Test
    void v3CreateDynamicOmittedOptionalFieldsMapToNullAndRepeatedFieldsMapToEmptyLists() throws Exception {
        when(punishmentLifecycleService.createMinecraftPunishment(same(server), any()))
            .thenReturn("punishment-2");

        CreatePunishmentRequest request = CreatePunishmentRequest.newBuilder()
            .setTargetUuid(PLAYER_UUID)
            .setTypeOrdinal(0)
            .build();

        MvcResult result = performV3CreateDynamic(request)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentCreateResponse response =
            PunishmentCreateResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("punishment-2", response.getPunishmentId());

        ArgumentCaptor<MinecraftPunishmentController.MinecraftCreatePunishmentRequest> requestCaptor =
            ArgumentCaptor.forClass(MinecraftPunishmentController.MinecraftCreatePunishmentRequest.class);
        verify(punishmentLifecycleService).createMinecraftPunishment(same(server), requestCaptor.capture());
        MinecraftPunishmentController.MinecraftCreatePunishmentRequest legacyRequest = requestCaptor.getValue();
        assertEquals(PLAYER_UUID, legacyRequest.targetUuid());
        assertNull(legacyRequest.issuerName());
        assertNull(legacyRequest.issuerId());
        assertNull(legacyRequest.reason());
        assertNull(legacyRequest.duration());
        assertNull(legacyRequest.data());
        assertEquals(List.of(), legacyRequest.notes());
        assertEquals(List.of(), legacyRequest.attachedTicketIds());
        assertNull(legacyRequest.severity());
        assertNull(legacyRequest.status());
    }

    @Test
    void v3CreateSuccessConvertsAllFieldsAndDataStructAndReturnsEmptyBinaryResponseWithoutPunishmentId() throws Exception {
        when(punishmentLifecycleService.createMinecraftPunishment(same(server), any()))
            .thenReturn("punishment-hidden");

        CreatePunishmentRequest request = validCreatePunishmentRequest().toBuilder()
            .setData(Struct.newBuilder()
                .putFields("flag", Value.newBuilder().setBoolValue(true).build())
                .putFields("score", Value.newBuilder().setNumberValue(12.5).build())
                .putFields("text", Value.newBuilder().setStringValue("value").build())
                .putFields("nullish", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
                .putFields("list", Value.newBuilder().setListValue(ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("first").build())
                    .addValues(Value.newBuilder().setNumberValue(2).build())
                    .addValues(Value.newBuilder().setBoolValue(false).build())
                    .build()).build())
                .putFields("nested", Value.newBuilder().setStructValue(Struct.newBuilder()
                    .putFields("child", Value.newBuilder().setStringValue("inside").build())
                    .build()).build())
                .build())
            .build();

        MvcResult result = performV3Create(request)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        byte[] responseBody = result.getResponse().getContentAsByteArray();
        assertEquals(0, responseBody.length);
        assertEquals(Empty.getDefaultInstance(), Empty.parseFrom(responseBody));
        assertEquals("", PunishmentCreateResponse.parseFrom(responseBody).getPunishmentId());

        ArgumentCaptor<MinecraftPunishmentController.MinecraftCreatePunishmentRequest> requestCaptor =
            ArgumentCaptor.forClass(MinecraftPunishmentController.MinecraftCreatePunishmentRequest.class);
        verify(punishmentLifecycleService).createMinecraftPunishment(same(server), requestCaptor.capture());
        MinecraftPunishmentController.MinecraftCreatePunishmentRequest legacyRequest = requestCaptor.getValue();
        assertEquals(PLAYER_UUID, legacyRequest.targetUuid());
        assertEquals("Mod", legacyRequest.issuerName());
        assertEquals("issuer-1", legacyRequest.issuerId());
        assertEquals(7, legacyRequest.typeOrdinal());
        assertEquals("Rule violation", legacyRequest.reason());
        assertEquals(3600L, legacyRequest.duration());
        assertEquals(List.of("note-1", "note-2"), legacyRequest.notes());
        assertEquals(List.of("ticket-1", "ticket-2"), legacyRequest.attachedTicketIds());
        assertEquals("regular", legacyRequest.severity());
        assertEquals("Queued", legacyRequest.status());
        assertEquals(true, legacyRequest.data().get("flag"));
        assertEquals(12.5, legacyRequest.data().get("score"));
        assertEquals("value", legacyRequest.data().get("text"));
        assertNull(legacyRequest.data().get("nullish"));
        assertEquals(List.of("first", 2.0, false), legacyRequest.data().get("list"));
        assertEquals(Map.of("child", "inside"), legacyRequest.data().get("nested"));
    }

    @Test
    void v3CreateOmittedOptionalFieldsMapToNullAndRepeatedFieldsMapToEmptyLists() throws Exception {
        when(punishmentLifecycleService.createMinecraftPunishment(same(server), any()))
            .thenReturn("punishment-hidden");

        CreatePunishmentRequest request = CreatePunishmentRequest.newBuilder()
            .setTargetUuid(PLAYER_UUID)
            .setTypeOrdinal(0)
            .build();

        performV3Create(request)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF));

        ArgumentCaptor<MinecraftPunishmentController.MinecraftCreatePunishmentRequest> requestCaptor =
            ArgumentCaptor.forClass(MinecraftPunishmentController.MinecraftCreatePunishmentRequest.class);
        verify(punishmentLifecycleService).createMinecraftPunishment(same(server), requestCaptor.capture());
        MinecraftPunishmentController.MinecraftCreatePunishmentRequest legacyRequest = requestCaptor.getValue();
        assertEquals(PLAYER_UUID, legacyRequest.targetUuid());
        assertNull(legacyRequest.issuerName());
        assertNull(legacyRequest.issuerId());
        assertNull(legacyRequest.reason());
        assertNull(legacyRequest.duration());
        assertNull(legacyRequest.data());
        assertEquals(List.of(), legacyRequest.notes());
        assertEquals(List.of(), legacyRequest.attachedTicketIds());
        assertNull(legacyRequest.severity());
        assertNull(legacyRequest.status());
    }

    @Test
    void v3CreateRejectsTooManyDataEntriesWithBinaryApiErrorAndSkipsService() throws Exception {
        Struct.Builder data = Struct.newBuilder();
        for (String key : numberedIds("data-", 51)) {
            data.putFields(key, Value.newBuilder().setStringValue("value").build());
        }
        CreatePunishmentRequest request = validCreatePunishmentRequest().toBuilder()
            .setData(data.build())
            .build();

        MvcResult result = performV3Create(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertEquals("data must contain no more than 50 entries", error.getMessage());
        assertTrue(error.getFieldViolationsList().isEmpty());
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3CreateRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/create")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3CreateRejectsJsonAcceptWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/create")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(MediaType.APPLICATION_JSON)
                .content(validCreatePunishmentRequest().toByteArray()))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3CreateValidationRejectsInvalidUuidWithBinaryApiErrorAndSkipsService() throws Exception {
        CreatePunishmentRequest request = validCreatePunishmentRequest().toBuilder()
            .setTargetUuid("not-a-uuid")
            .build();

        MvcResult result = performV3Create(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("target_uuid")));
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v1CreateStillReturnsJsonCompatibleEmptyOkResponse() throws Exception {
        when(punishmentLifecycleService.createMinecraftPunishment(same(server), any()))
            .thenReturn("legacy-punishment");

        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS + "/create")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "targetUuid": "11111111-2222-3333-4444-555555555555",
                      "type_ordinal": 1
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();

        assertEquals(0, result.getResponse().getContentAsByteArray().length);
        assertNull(result.getResponse().getContentType());
        verify(punishmentLifecycleService).createMinecraftPunishment(same(server), any());
    }

    @Test
    void v3CreateDynamicValidationRejectsInvalidFieldsAndSkipsService() throws Exception {
        CreatePunishmentRequest request = CreatePunishmentRequest.newBuilder()
            .setTargetUuid("not-a-uuid")
            .setIssuerName("x".repeat(65))
            .setIssuerId("x".repeat(65))
            .setTypeOrdinal(-1)
            .setReason("x".repeat(1001))
            .setDuration(-1L)
            .addAllNotes(List.of("valid-note", "x".repeat(4001)))
            .addAllAttachedTicketIds(List.of("ticket-1", "x".repeat(65), "   "))
            .setSeverity("x".repeat(33))
            .setStatus("x".repeat(33))
            .build();

        MvcResult result = performV3CreateDynamic(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("target_uuid")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_name")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("type_ordinal")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("reason")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("duration")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("notes")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("attached_ticket_ids")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("severity")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("status")));
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3CreateDynamicRejectsTooManyNotesAndAttachedTicketIdsAndSkipsService() throws Exception {
        CreatePunishmentRequest request = validCreatePunishmentRequest().toBuilder()
            .clearNotes()
            .addAllNotes(numberedIds("note-", 51))
            .clearAttachedTicketIds()
            .addAllAttachedTicketIds(numberedIds("ticket-", 51))
            .build();

        MvcResult result = performV3CreateDynamic(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("notes")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("attached_ticket_ids")));
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3CreateDynamicRejectsTooManyDataEntriesWithBinaryApiErrorAndSkipsService() throws Exception {
        Struct.Builder data = Struct.newBuilder();
        for (String key : numberedIds("data-", 51)) {
            data.putFields(key, Value.newBuilder().setStringValue("value").build());
        }
        CreatePunishmentRequest request = validCreatePunishmentRequest().toBuilder()
            .setData(data.build())
            .build();

        MvcResult result = performV3CreateDynamic(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertEquals("data must contain no more than 50 entries", error.getMessage());
        assertTrue(error.getFieldViolationsList().isEmpty());
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3CreateDynamicRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/dynamic")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v1CreateDynamicInvalidUuidStillReturnsJsonBadRequest() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS + "/dynamic")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "targetUuid": "not-a-uuid",
                      "type_ordinal": 1
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3AcknowledgeSuccessReturnsBinaryStatusMessageAndCallsService() throws Exception {
        when(punishmentLifecycleService.acknowledgePunishment(same(server), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment acknowledged", true, 1));

        MvcResult result = performV3Acknowledge(validRequest("punishment-1"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentAcknowledgeResponse response =
            PunishmentAcknowledgeResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("Punishment acknowledged", response.getMessage());

        ArgumentCaptor<UUID> playerUuidCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> punishmentIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(punishmentLifecycleService).acknowledgePunishment(
            same(server),
            playerUuidCaptor.capture(),
            punishmentIdCaptor.capture()
        );
        assertEquals(UUID.fromString(PLAYER_UUID), playerUuidCaptor.getValue());
        assertEquals("punishment-1", punishmentIdCaptor.getValue());
    }

    @Test
    void v3AcknowledgeNoOpReturnsBinaryOkStatusMessage() throws Exception {
        when(punishmentLifecycleService.acknowledgePunishment(same(server), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NO_OP, "Already acknowledged", false, 0));

        MvcResult result = performV3Acknowledge(validRequest("punishment-1"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentAcknowledgeResponse response =
            PunishmentAcknowledgeResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("Already acknowledged", response.getMessage());
    }

    @Test
    void v3AcknowledgeNotFoundReturnsBinaryNotFoundStatusMessage() throws Exception {
        when(punishmentLifecycleService.acknowledgePunishment(same(server), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0));

        MvcResult result = performV3Acknowledge(validRequest("missing-punishment"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentAcknowledgeResponse response =
            PunishmentAcknowledgeResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertEquals("Punishment not found", response.getMessage());
    }

    @Test
    void v3AcknowledgeInvalidRequestReturnsBinaryBadRequestStatusMessage() throws Exception {
        when(punishmentLifecycleService.acknowledgePunishment(same(server), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.INVALID_REQUEST, "Invalid acknowledgement", false, 0));

        MvcResult result = performV3Acknowledge(validRequest("punishment-1"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentAcknowledgeResponse response =
            PunishmentAcknowledgeResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, response.getStatus());
        assertEquals("Invalid acknowledgement", response.getMessage());
    }

    @Test
    void v3AcknowledgeValidationFailureReturnsBinaryApiErrorWithFieldViolations() throws Exception {
        PunishmentAcknowledgeRequest request = PunishmentAcknowledgeRequest.newBuilder()
            .setPunishmentId("   ")
            .setPlayerUuid("not-a-uuid")
            .setExecutedAt("x".repeat(65))
            .setErrorMessage("x".repeat(501))
            .setSuccess(false)
            .build();

        MvcResult result = performV3Acknowledge(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("punishment_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("player_uuid")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("executed_at")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("error_message")));
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3AcknowledgeRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/acknowledge")
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
    void v3StatWipeAcknowledgeSuccessReturnsBinaryStatusSuccessMessageAndUsesPathPunishmentId() throws Exception {
        when(punishmentMutationService.acknowledgeStatWipe(same(server), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Stat wipe acknowledged", true, 1));

        MvcResult result = performV3StatWipeAcknowledge("path-punishment", validStatWipeRequest("body-punishment"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        StatWipeAcknowledgeResponse response =
            StatWipeAcknowledgeResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("Stat wipe acknowledged", response.getMessage());

        verify(punishmentMutationService).acknowledgeStatWipe(server, "path-punishment");
        verifyNoMoreInteractions(punishmentMutationService);
    }

    @Test
    void v3StatWipeAcknowledgeNoOpReturnsBinaryOkStatusMessageAndDefaultSuccess() throws Exception {
        when(punishmentMutationService.acknowledgeStatWipe(same(server), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NO_OP, "Already acknowledged", false, 0));

        MvcResult result = performV3StatWipeAcknowledge("punishment-1", validStatWipeRequest("punishment-1"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        StatWipeAcknowledgeResponse response =
            StatWipeAcknowledgeResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Already acknowledged", response.getMessage());
    }

    @Test
    void v3StatWipeAcknowledgeNotFoundReturnsBinaryNotFoundStatusMessage() throws Exception {
        when(punishmentMutationService.acknowledgeStatWipe(same(server), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0));

        MvcResult result = performV3StatWipeAcknowledge("missing-punishment", validStatWipeRequest("missing-punishment"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        StatWipeAcknowledgeResponse response =
            StatWipeAcknowledgeResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Punishment not found", response.getMessage());
    }

    @Test
    void v3StatWipeAcknowledgeInvalidRequestReturnsBinaryBadRequestStatusMessage() throws Exception {
        when(punishmentMutationService.acknowledgeStatWipe(same(server), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.INVALID_REQUEST, "Invalid acknowledgement", false, 0));

        MvcResult result = performV3StatWipeAcknowledge("punishment-1", validStatWipeRequest("punishment-1"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        StatWipeAcknowledgeResponse response =
            StatWipeAcknowledgeResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Invalid acknowledgement", response.getMessage());
    }

    @Test
    void v3StatWipeAcknowledgeValidationFailureReturnsBinaryApiErrorAndSkipsService() throws Exception {
        StatWipeAcknowledgeRequest request = StatWipeAcknowledgeRequest.newBuilder()
            .setPunishmentId("   ")
            .setServerName("x".repeat(65))
            .setSuccess(true)
            .build();

        MvcResult result = performV3StatWipeAcknowledge("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("punishment_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("server_name")));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v3StatWipeAcknowledgeRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/punishment-1/stat-wipe-acknowledge")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v3ToggleOptionSuccessReturnsBinaryStatusSuccessMessageAndCallsService() throws Exception {
        when(punishmentMutationService.toggleOption(same(server), any(), any(), any(Boolean.class), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Option toggled", true, 1));

        MvcResult result = performV3ToggleOption("punishment-1", validToggleRequest("punishment-1"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        TogglePunishmentOptionResponse response =
            TogglePunishmentOptionResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("Option toggled", response.getMessage());

        ArgumentCaptor<String> punishmentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> optionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> enabledCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> issuerNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(punishmentMutationService).toggleOption(
            same(server),
            punishmentIdCaptor.capture(),
            optionCaptor.capture(),
            enabledCaptor.capture(),
            issuerNameCaptor.capture(),
            issuerIdCaptor.capture()
        );
        assertEquals("punishment-1", punishmentIdCaptor.getValue());
        assertEquals("STAT_WIPE", optionCaptor.getValue());
        assertTrue(enabledCaptor.getValue());
        assertEquals("Mod", issuerNameCaptor.getValue());
        assertEquals("issuer-1", issuerIdCaptor.getValue());
    }

    @Test
    void v3AddNoteSuccessReturnsBinaryStatusSuccessMessageAndCallsService() throws Exception {
        when(punishmentEvidenceService.addPunishmentNote(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Note added", true, 1));

        MvcResult result = performV3AddNote("path-punishment", validAddNoteRequest("body-punishment"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        AddPunishmentNoteResponse response =
            AddPunishmentNoteResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.hasSuccess());
        assertTrue(response.getSuccess());
        assertEquals("Note added", response.getMessage());

        ArgumentCaptor<String> punishmentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(punishmentEvidenceService).addPunishmentNote(
            same(server),
            punishmentIdCaptor.capture(),
            noteCaptor.capture(),
            issuerNameCaptor.capture(),
            issuerIdCaptor.capture()
        );
        assertEquals("path-punishment", punishmentIdCaptor.getValue());
        assertEquals("Needs review", noteCaptor.getValue());
        assertEquals("Mod", issuerNameCaptor.getValue());
        assertEquals("issuer-1", issuerIdCaptor.getValue());
    }

    @Test
    void v3AddNoteNotFoundReturnsBinaryNotFoundStatusMessageWithoutSuccess() throws Exception {
        when(punishmentEvidenceService.addPunishmentNote(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0));

        MvcResult result = performV3AddNote("missing-punishment", validAddNoteRequest("missing-punishment"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        AddPunishmentNoteResponse response =
            AddPunishmentNoteResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.hasSuccess());
        assertEquals("Punishment not found", response.getMessage());
    }

    @Test
    void v3AddNoteAcceptsOmittedBodyPunishmentIdAndUsesPathPunishmentId() throws Exception {
        when(punishmentEvidenceService.addPunishmentNote(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Note added", true, 1));

        AddPunishmentNoteRequest request = AddPunishmentNoteRequest.newBuilder()
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setNote("Needs review")
            .build();

        performV3AddNote("path-only-punishment", request)
            .andExpect(status().isOk());

        verify(punishmentEvidenceService).addPunishmentNote(
            same(server),
            eq("path-only-punishment"),
            eq("Needs review"),
            eq("Mod"),
            eq("issuer-1")
        );
    }

    @Test
    void v3AddNoteUsesPathPunishmentIdWhenBodyDiffers() throws Exception {
        when(punishmentEvidenceService.addPunishmentNote(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Note added", true, 1));

        performV3AddNote("path-punishment", validAddNoteRequest("body-punishment"))
            .andExpect(status().isOk());

        verify(punishmentEvidenceService).addPunishmentNote(
            same(server),
            eq("path-punishment"),
            eq("Needs review"),
            eq("Mod"),
            eq("issuer-1")
        );
    }

    @Test
    void v3AddNoteOmittedIssuerFieldsMapToNullServiceArguments() throws Exception {
        when(punishmentEvidenceService.addPunishmentNote(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Note added", true, 1));

        AddPunishmentNoteRequest request = AddPunishmentNoteRequest.newBuilder()
            .setPunishmentId("punishment-1")
            .setNote("Needs review")
            .build();

        performV3AddNote("punishment-1", request)
            .andExpect(status().isOk());

        verify(punishmentEvidenceService).addPunishmentNote(
            same(server),
            eq("punishment-1"),
            eq("Needs review"),
            isNull(),
            isNull()
        );
    }

    @Test
    void v3AddNoteValidationFailureReturnsBinaryApiErrorAndSkipsService() throws Exception {
        AddPunishmentNoteRequest request = AddPunishmentNoteRequest.newBuilder()
            .setPunishmentId("x".repeat(65))
            .setIssuerName("x".repeat(65))
            .setIssuerId("x".repeat(65))
            .setNote("   ")
            .build();

        MvcResult result = performV3AddNote("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("punishment_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_name")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("note")));
        verifyNoInteractions(punishmentEvidenceService);
    }

    @Test
    void v3AddNoteRejectsOversizedNoteWithBinaryApiErrorAndSkipsService() throws Exception {
        AddPunishmentNoteRequest request = validAddNoteRequest("punishment-1").toBuilder()
            .setNote("x".repeat(4001))
            .build();

        MvcResult result = performV3AddNote("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("note")));
        verifyNoInteractions(punishmentEvidenceService);
    }

    @Test
    void v3AddNoteRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/punishment-1/note")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentEvidenceService);
    }

    @Test
    void v3AddEvidenceSuccessReturnsBinaryStatusSuccessMessageAndCallsService() throws Exception {
        when(punishmentEvidenceService.addEvidence(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Evidence added", true, 1));

        MvcResult result = performV3AddEvidence("path-punishment", validAddEvidenceRequest("body-punishment"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        AddPunishmentEvidenceResponse response =
            AddPunishmentEvidenceResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.hasSuccess());
        assertTrue(response.getSuccess());
        assertEquals("Evidence added", response.getMessage());

        ArgumentCaptor<String> punishmentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> evidenceUrlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(punishmentEvidenceService).addEvidence(
            same(server),
            punishmentIdCaptor.capture(),
            evidenceUrlCaptor.capture(),
            issuerNameCaptor.capture(),
            issuerIdCaptor.capture()
        );
        assertEquals("path-punishment", punishmentIdCaptor.getValue());
        assertEquals("https://example.com/evidence.png", evidenceUrlCaptor.getValue());
        assertEquals("Mod", issuerNameCaptor.getValue());
        assertEquals("issuer-1", issuerIdCaptor.getValue());
    }

    @Test
    void v3AddEvidenceNotFoundReturnsBinaryNotFoundStatusMessageWithoutSuccess() throws Exception {
        when(punishmentEvidenceService.addEvidence(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0));

        MvcResult result = performV3AddEvidence("missing-punishment", validAddEvidenceRequest("missing-punishment"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        AddPunishmentEvidenceResponse response =
            AddPunishmentEvidenceResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.hasSuccess());
        assertEquals("Punishment not found", response.getMessage());
    }

    @Test
    void v3AddEvidenceAcceptsOmittedBodyPunishmentIdAndUsesPathPunishmentId() throws Exception {
        when(punishmentEvidenceService.addEvidence(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Evidence added", true, 1));

        AddPunishmentEvidenceRequest request = AddPunishmentEvidenceRequest.newBuilder()
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setEvidenceUrl("https://example.com/evidence.png")
            .build();

        performV3AddEvidence("path-only-punishment", request)
            .andExpect(status().isOk());

        verify(punishmentEvidenceService).addEvidence(
            same(server),
            eq("path-only-punishment"),
            eq("https://example.com/evidence.png"),
            eq("Mod"),
            eq("issuer-1")
        );
    }

    @Test
    void v3AddEvidenceUsesPathPunishmentIdWhenBodyDiffers() throws Exception {
        when(punishmentEvidenceService.addEvidence(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Evidence added", true, 1));

        performV3AddEvidence("path-punishment", validAddEvidenceRequest("body-punishment"))
            .andExpect(status().isOk());

        verify(punishmentEvidenceService).addEvidence(
            same(server),
            eq("path-punishment"),
            eq("https://example.com/evidence.png"),
            eq("Mod"),
            eq("issuer-1")
        );
    }

    @Test
    void v3AddEvidenceOmittedIssuerFieldsMapToNullServiceArguments() throws Exception {
        when(punishmentEvidenceService.addEvidence(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Evidence added", true, 1));

        AddPunishmentEvidenceRequest request = AddPunishmentEvidenceRequest.newBuilder()
            .setPunishmentId("punishment-1")
            .setEvidenceUrl("https://example.com/evidence.png")
            .build();

        performV3AddEvidence("punishment-1", request)
            .andExpect(status().isOk());

        verify(punishmentEvidenceService).addEvidence(
            same(server),
            eq("punishment-1"),
            eq("https://example.com/evidence.png"),
            isNull(),
            isNull()
        );
    }

    @Test
    void v3AddEvidenceRejectsBlankEvidenceUrlWithBinaryApiErrorAndSkipsService() throws Exception {
        AddPunishmentEvidenceRequest request = validAddEvidenceRequest("punishment-1").toBuilder()
            .setEvidenceUrl("   ")
            .build();

        MvcResult result = performV3AddEvidence("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("evidence_url")));
        verifyNoInteractions(punishmentEvidenceService);
    }

    @Test
    void v3AddEvidenceRejectsOversizedEvidenceUrlWithBinaryApiErrorAndSkipsService() throws Exception {
        AddPunishmentEvidenceRequest request = validAddEvidenceRequest("punishment-1").toBuilder()
            .setEvidenceUrl("x".repeat(2049))
            .build();

        MvcResult result = performV3AddEvidence("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("evidence_url")));
        verifyNoInteractions(punishmentEvidenceService);
    }

    @Test
    void v3AddEvidenceRejectsOversizedMetadataWithBinaryApiErrorAndSkipsService() throws Exception {
        AddPunishmentEvidenceRequest request = validAddEvidenceRequest("punishment-1").toBuilder()
            .setPunishmentId("x".repeat(65))
            .setIssuerName("x".repeat(65))
            .setIssuerId("x".repeat(65))
            .build();

        MvcResult result = performV3AddEvidence("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("punishment_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_name")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_id")));
        verifyNoInteractions(punishmentEvidenceService);
    }

    @Test
    void v3AddEvidenceRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/punishment-1/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentEvidenceService);
    }

    @Test
    void v3CreateUploadTokenSuccessReturnsBinaryStatusTokenAndCallsServiceWithPathIdAndIssuerName() throws Exception {
        when(punishmentQueryService.createEvidenceUploadToken(same(server), any(), any()))
            .thenReturn(Optional.of("upload-token-1"));

        MvcResult result = performV3CreateUploadToken("path-punishment", validUploadTokenRequest())
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        EvidenceUploadTokenResponse response =
            EvidenceUploadTokenResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("upload-token-1", response.getToken());

        ArgumentCaptor<String> punishmentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(punishmentQueryService).createEvidenceUploadToken(
            same(server),
            punishmentIdCaptor.capture(),
            issuerNameCaptor.capture()
        );
        assertEquals("path-punishment", punishmentIdCaptor.getValue());
        assertEquals("Mod", issuerNameCaptor.getValue());
    }

    @Test
    void v3CreateUploadTokenNotFoundReturnsBinaryNotFoundStatusAndDefaultToken() throws Exception {
        when(punishmentQueryService.createEvidenceUploadToken(same(server), any(), any()))
            .thenReturn(Optional.empty());

        MvcResult result = performV3CreateUploadToken("missing-punishment", validUploadTokenRequest())
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        EvidenceUploadTokenResponse response =
            EvidenceUploadTokenResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertEquals("", response.getToken());
    }

    @Test
    void v3CreateUploadTokenOmittedIssuerFieldsMapIssuerNameToNullServiceArgument() throws Exception {
        when(punishmentQueryService.createEvidenceUploadToken(same(server), any(), any()))
            .thenReturn(Optional.of("upload-token-1"));

        performV3CreateUploadToken("punishment-1", CreateEvidenceUploadTokenRequest.newBuilder().build())
            .andExpect(status().isOk());

        verify(punishmentQueryService).createEvidenceUploadToken(
            same(server),
            eq("punishment-1"),
            isNull()
        );
    }

    @Test
    void v3CreateUploadTokenIssuerIdDoesNotAffectServiceArguments() throws Exception {
        when(punishmentQueryService.createEvidenceUploadToken(same(server), any(), any()))
            .thenReturn(Optional.of("upload-token-1"));

        CreateEvidenceUploadTokenRequest request = validUploadTokenRequest().toBuilder()
            .setIssuerId("issuer-1")
            .build();

        performV3CreateUploadToken("punishment-1", request)
            .andExpect(status().isOk());

        verify(punishmentQueryService).createEvidenceUploadToken(
            same(server),
            eq("punishment-1"),
            eq("Mod")
        );
    }

    @Test
    void v3CreateUploadTokenRejectsOversizedMetadataWithBinaryApiErrorAndSkipsService() throws Exception {
        CreateEvidenceUploadTokenRequest request = CreateEvidenceUploadTokenRequest.newBuilder()
            .setIssuerName("x".repeat(65))
            .setIssuerId("x".repeat(65))
            .build();

        MvcResult result = performV3CreateUploadToken("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_name")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_id")));
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v3CreateUploadTokenRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/punishment-1/upload-token")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v3PardonSuccessReturnsBinaryStatusSuccessCountMessageAndCallsService() throws Exception {
        when(punishmentLifecycleService.pardonPunishment(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment pardoned", true, 1));

        MvcResult result = performV3Pardon("path-punishment", validPardonRequest())
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PardonResponse response = PardonResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals(1, response.getPardonedCount());
        assertEquals("Punishment pardoned", response.getMessage());

        ArgumentCaptor<String> punishmentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(punishmentLifecycleService).pardonPunishment(
            same(server),
            punishmentIdCaptor.capture(),
            issuerNameCaptor.capture(),
            issuerIdCaptor.capture(),
            reasonCaptor.capture()
        );
        assertEquals("path-punishment", punishmentIdCaptor.getValue());
        assertEquals("Mod", issuerNameCaptor.getValue());
        assertEquals("issuer-1", issuerIdCaptor.getValue());
        assertEquals("Accepted appeal", reasonCaptor.getValue());
    }

    @Test
    void v3PardonNoOpReturnsBinaryOkStatusFalseCountZeroMessage() throws Exception {
        when(punishmentLifecycleService.pardonPunishment(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NO_OP, "Already pardoned", false, 0));

        MvcResult result = performV3Pardon("punishment-1", validPardonRequest())
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PardonResponse response = PardonResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals(0, response.getPardonedCount());
        assertEquals("Already pardoned", response.getMessage());
    }

    @Test
    void v3PardonNotFoundReturnsBinaryNotFoundStatusMessageAndDefaultFailureFields() throws Exception {
        when(punishmentLifecycleService.pardonPunishment(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0));

        MvcResult result = performV3Pardon("missing-punishment", validPardonRequest())
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PardonResponse response = PardonResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals(0, response.getPardonedCount());
        assertEquals("Punishment not found", response.getMessage());
    }

    @Test
    void v3PardonInvalidRequestReturnsBinaryBadRequestStatusMessageAndDefaultFailureFields() throws Exception {
        when(punishmentLifecycleService.pardonPunishment(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.INVALID_REQUEST, "Invalid pardon", false, 0));

        MvcResult result = performV3Pardon("punishment-1", validPardonRequest())
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PardonResponse response = PardonResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals(0, response.getPardonedCount());
        assertEquals("Invalid pardon", response.getMessage());
    }

    @Test
    void v3PardonOmittedIssuerAndReasonFieldsMapToNullServiceArguments() throws Exception {
        when(punishmentLifecycleService.pardonPunishment(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment pardoned", true, 1));

        performV3Pardon("punishment-1", PardonPunishmentRequest.newBuilder()
                .setExpectedType("BAN")
                .build())
            .andExpect(status().isOk());

        verify(punishmentLifecycleService).pardonPunishment(
            same(server),
            eq("punishment-1"),
            isNull(),
            isNull(),
            isNull()
        );
    }

    @Test
    void v3PardonExpectedTypeDoesNotAffectServiceArguments() throws Exception {
        when(punishmentLifecycleService.pardonPunishment(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment pardoned", true, 1));

        PardonPunishmentRequest request = validPardonRequest().toBuilder()
            .setExpectedType("BAN")
            .build();

        performV3Pardon("punishment-1", request)
            .andExpect(status().isOk());

        verify(punishmentLifecycleService).pardonPunishment(
            same(server),
            eq("punishment-1"),
            eq("Mod"),
            eq("issuer-1"),
            eq("Accepted appeal")
        );
    }

    @Test
    void v3PardonValidationRejectsOversizedMetadataWithBinaryApiErrorAndSkipsService() throws Exception {
        PardonPunishmentRequest request = PardonPunishmentRequest.newBuilder()
            .setIssuerName("x".repeat(65))
            .setIssuerId("x".repeat(65))
            .setReason("x".repeat(501))
            .setExpectedType("x".repeat(65))
            .build();

        MvcResult result = performV3Pardon("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_name")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("reason")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("expected_type")));
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3PardonRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/punishment-1/pardon")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v3ToggleOptionInvalidRequestReturnsBinaryBadRequestStatusMessage() throws Exception {
        when(punishmentMutationService.toggleOption(same(server), any(), any(), any(Boolean.class), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.INVALID_REQUEST, "Invalid option", false, 0));

        MvcResult result = performV3ToggleOption("punishment-1", validToggleRequest("punishment-1"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        TogglePunishmentOptionResponse response =
            TogglePunishmentOptionResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, response.getStatus());
        assertFalse(response.hasSuccess());
        assertEquals("Invalid option", response.getMessage());
    }

    @Test
    void v3ToggleOptionNotFoundReturnsBinaryNotFoundStatusMessage() throws Exception {
        when(punishmentMutationService.toggleOption(same(server), any(), any(), any(Boolean.class), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0));

        MvcResult result = performV3ToggleOption("missing-punishment", validToggleRequest("missing-punishment"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        TogglePunishmentOptionResponse response =
            TogglePunishmentOptionResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.hasSuccess());
        assertEquals("Punishment not found", response.getMessage());
    }

    @Test
    void v3ToggleOptionUsesPathPunishmentIdWhenBodyDiffers() throws Exception {
        when(punishmentMutationService.toggleOption(same(server), any(), any(), any(Boolean.class), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Option toggled", true, 1));

        performV3ToggleOption("path-punishment", validToggleRequest("body-punishment"))
            .andExpect(status().isOk());

        verify(punishmentMutationService).toggleOption(
            same(server),
            eq("path-punishment"),
            eq("STAT_WIPE"),
            eq(true),
            eq("Mod"),
            eq("issuer-1")
        );
    }

    @Test
    void v3ToggleOptionAcceptsOmittedBodyPunishmentIdAndUsesPathPunishmentId() throws Exception {
        when(punishmentMutationService.toggleOption(same(server), any(), any(), any(Boolean.class), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Option toggled", true, 1));

        TogglePunishmentOptionRequest request = TogglePunishmentOptionRequest.newBuilder()
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setOption("STAT_WIPE")
            .setEnabled(true)
            .build();

        performV3ToggleOption("path-only-punishment", request)
            .andExpect(status().isOk());

        verify(punishmentMutationService).toggleOption(
            same(server),
            eq("path-only-punishment"),
            eq("STAT_WIPE"),
            eq(true),
            eq("Mod"),
            eq("issuer-1")
        );
    }

    @Test
    void v3ToggleOptionOmittedIssuerFieldsMapToNullServiceArguments() throws Exception {
        when(punishmentMutationService.toggleOption(same(server), any(), any(), any(Boolean.class), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Option toggled", true, 1));

        TogglePunishmentOptionRequest request = TogglePunishmentOptionRequest.newBuilder()
            .setPunishmentId("punishment-1")
            .setOption("STAT_WIPE")
            .setEnabled(false)
            .build();

        performV3ToggleOption("punishment-1", request)
            .andExpect(status().isOk());

        verify(punishmentMutationService).toggleOption(
            same(server),
            eq("punishment-1"),
            eq("STAT_WIPE"),
            eq(false),
            isNull(),
            isNull()
        );
    }

    @Test
    void v3ToggleOptionValidationFailureReturnsBinaryApiErrorAndSkipsService() throws Exception {
        TogglePunishmentOptionRequest request = TogglePunishmentOptionRequest.newBuilder()
            .setPunishmentId("   ")
            .setIssuerName("x".repeat(65))
            .setIssuerId("x".repeat(65))
            .setOption("   ")
            .setEnabled(true)
            .build();

        MvcResult result = performV3ToggleOption("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_name")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("option")));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v3ToggleOptionRejectsOversizedOptionWithBinaryApiErrorAndSkipsService() throws Exception {
        TogglePunishmentOptionRequest request = validToggleRequest("punishment-1").toBuilder()
            .setOption("x".repeat(65))
            .build();

        MvcResult result = performV3ToggleOption("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("option")));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v3ToggleOptionRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/punishment-1/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v3ChangeDurationSuccessReturnsBinaryStatusSuccessMessageAndCallsService() throws Exception {
        when(punishmentMutationService.changeDuration(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Duration changed", true, 1));

        MvcResult result = performV3ChangeDuration("path-punishment", validChangeDurationRequest("body-punishment"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ChangePunishmentDurationResponse response =
            ChangePunishmentDurationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.hasSuccess());
        assertTrue(response.getSuccess());
        assertEquals("Duration changed", response.getMessage());

        ArgumentCaptor<String> punishmentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> newDurationCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> issuerNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(punishmentMutationService).changeDuration(
            same(server),
            punishmentIdCaptor.capture(),
            newDurationCaptor.capture(),
            issuerNameCaptor.capture(),
            issuerIdCaptor.capture()
        );
        assertEquals("path-punishment", punishmentIdCaptor.getValue());
        assertEquals(3600L, newDurationCaptor.getValue());
        assertEquals("Mod", issuerNameCaptor.getValue());
        assertEquals("issuer-1", issuerIdCaptor.getValue());
    }

    @Test
    void v3ChangeDurationNotFoundReturnsBinaryNotFoundStatusMessageWithoutSuccess() throws Exception {
        when(punishmentMutationService.changeDuration(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0));

        MvcResult result = performV3ChangeDuration("missing-punishment", validChangeDurationRequest("missing-punishment"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ChangePunishmentDurationResponse response =
            ChangePunishmentDurationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.hasSuccess());
        assertEquals("Punishment not found", response.getMessage());
    }

    @Test
    void v3ChangeDurationInvalidRequestReturnsBinaryBadRequestStatusMessageAndDefaultFailureFields() throws Exception {
        when(punishmentMutationService.changeDuration(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.INVALID_REQUEST, "Invalid duration", false, 0));

        MvcResult result = performV3ChangeDuration("punishment-1", validChangeDurationRequest("punishment-1"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ChangePunishmentDurationResponse response =
            ChangePunishmentDurationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, response.getStatus());
        assertFalse(response.hasSuccess());
        assertEquals("Invalid duration", response.getMessage());
    }

    @Test
    void v3ChangeDurationAcceptsOmittedBodyPunishmentIdAndUsesPathPunishmentId() throws Exception {
        when(punishmentMutationService.changeDuration(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Duration changed", true, 1));

        ChangePunishmentDurationRequest request = ChangePunishmentDurationRequest.newBuilder()
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setNewDuration(3600L)
            .build();

        performV3ChangeDuration("path-only-punishment", request)
            .andExpect(status().isOk());

        verify(punishmentMutationService).changeDuration(
            same(server),
            eq("path-only-punishment"),
            eq(3600L),
            eq("Mod"),
            eq("issuer-1")
        );
    }

    @Test
    void v3ChangeDurationUsesPathPunishmentIdWhenBodyDiffers() throws Exception {
        when(punishmentMutationService.changeDuration(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Duration changed", true, 1));

        performV3ChangeDuration("path-punishment", validChangeDurationRequest("body-punishment"))
            .andExpect(status().isOk());

        verify(punishmentMutationService).changeDuration(
            same(server),
            eq("path-punishment"),
            eq(3600L),
            eq("Mod"),
            eq("issuer-1")
        );
    }

    @Test
    void v3ChangeDurationOmittedIssuerFieldsMapToNullServiceArguments() throws Exception {
        when(punishmentMutationService.changeDuration(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Duration changed", true, 1));

        ChangePunishmentDurationRequest request = ChangePunishmentDurationRequest.newBuilder()
            .setPunishmentId("punishment-1")
            .setNewDuration(3600L)
            .build();

        performV3ChangeDuration("punishment-1", request)
            .andExpect(status().isOk());

        verify(punishmentMutationService).changeDuration(
            same(server),
            eq("punishment-1"),
            eq(3600L),
            isNull(),
            isNull()
        );
    }

    @Test
    void v3ChangeDurationOmittedNewDurationPassesNullAndReturnsSuccess() throws Exception {
        when(punishmentMutationService.changeDuration(same(server), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Duration changed", true, 1));

        ChangePunishmentDurationRequest request = ChangePunishmentDurationRequest.newBuilder()
            .setPunishmentId("punishment-1")
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .build();

        MvcResult result = performV3ChangeDuration("punishment-1", request)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ChangePunishmentDurationResponse response =
            ChangePunishmentDurationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.hasSuccess());
        assertTrue(response.getSuccess());

        verify(punishmentMutationService).changeDuration(
            same(server),
            eq("punishment-1"),
            isNull(),
            eq("Mod"),
            eq("issuer-1")
        );
    }

    @Test
    void v3ChangeDurationValidationFailureReturnsBinaryApiErrorAndSkipsService() throws Exception {
        ChangePunishmentDurationRequest request = ChangePunishmentDurationRequest.newBuilder()
            .setPunishmentId("x".repeat(65))
            .setIssuerName("x".repeat(65))
            .setIssuerId("x".repeat(65))
            .setNewDuration(-1L)
            .build();

        MvcResult result = performV3ChangeDuration("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("punishment_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_name")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("new_duration")));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v3ChangeDurationRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/punishment-1/duration")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v3ModifyTicketsSuccessReturnsBinaryStatusSuccessMessageAndCallsService() throws Exception {
        when(punishmentMutationService.modifyPunishmentTickets(same(server), any(), any(), any(), anyBoolean(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment tickets modified", true, 1));

        MvcResult result = performV3ModifyTickets("path-punishment", validModifyTicketsRequest("body-punishment"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ModifyPunishmentTicketsResponse response =
            ModifyPunishmentTicketsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.hasSuccess());
        assertTrue(response.getSuccess());
        assertEquals("Punishment tickets modified", response.getMessage());

        ArgumentCaptor<String> punishmentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> addTicketIdsCaptor = listCaptor();
        ArgumentCaptor<List<String>> removeTicketIdsCaptor = listCaptor();
        ArgumentCaptor<Boolean> modifyAssociatedTicketsCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> issuerNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(punishmentMutationService).modifyPunishmentTickets(
            same(server),
            punishmentIdCaptor.capture(),
            addTicketIdsCaptor.capture(),
            removeTicketIdsCaptor.capture(),
            modifyAssociatedTicketsCaptor.capture(),
            issuerNameCaptor.capture(),
            issuerIdCaptor.capture()
        );
        assertEquals("path-punishment", punishmentIdCaptor.getValue());
        assertEquals(List.of("ticket-add-1", "ticket-add-2"), addTicketIdsCaptor.getValue());
        assertEquals(List.of("ticket-remove-1"), removeTicketIdsCaptor.getValue());
        assertTrue(modifyAssociatedTicketsCaptor.getValue());
        assertEquals("Mod", issuerNameCaptor.getValue());
        assertEquals("issuer-1", issuerIdCaptor.getValue());
    }

    @Test
    void v3ModifyTicketsNotFoundReturnsBinaryNotFoundStatusMessageWithoutSuccess() throws Exception {
        when(punishmentMutationService.modifyPunishmentTickets(same(server), any(), any(), any(), anyBoolean(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Failed to modify punishment tickets", false, 0));

        MvcResult result = performV3ModifyTickets("missing-punishment", validModifyTicketsRequest("missing-punishment"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ModifyPunishmentTicketsResponse response =
            ModifyPunishmentTicketsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.hasSuccess());
        assertEquals("Failed to modify punishment tickets", response.getMessage());
    }

    @Test
    void v3ModifyTicketsAcceptsOmittedBodyPunishmentIdAndUsesPathPunishmentId() throws Exception {
        when(punishmentMutationService.modifyPunishmentTickets(same(server), any(), any(), any(), anyBoolean(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment tickets modified", true, 1));

        ModifyPunishmentTicketsRequest request = validModifyTicketsRequest("punishment-1").toBuilder()
            .clearPunishmentId()
            .build();

        performV3ModifyTickets("path-only-punishment", request)
            .andExpect(status().isOk());

        verify(punishmentMutationService).modifyPunishmentTickets(
            same(server),
            eq("path-only-punishment"),
            any(),
            any(),
            anyBoolean(),
            any(),
            any()
        );
    }

    @Test
    void v3ModifyTicketsUsesPathPunishmentIdWhenBodyDiffers() throws Exception {
        when(punishmentMutationService.modifyPunishmentTickets(same(server), any(), any(), any(), anyBoolean(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment tickets modified", true, 1));

        performV3ModifyTickets("path-punishment", validModifyTicketsRequest("body-punishment"))
            .andExpect(status().isOk());

        verify(punishmentMutationService).modifyPunishmentTickets(
            same(server),
            eq("path-punishment"),
            any(),
            any(),
            anyBoolean(),
            any(),
            any()
        );
    }

    @Test
    void v3ModifyTicketsOmittedIssuerFieldsMapToNullDtoFields() throws Exception {
        when(punishmentMutationService.modifyPunishmentTickets(same(server), any(), any(), any(), anyBoolean(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment tickets modified", true, 1));

        ModifyPunishmentTicketsRequest request = ModifyPunishmentTicketsRequest.newBuilder()
            .setPunishmentId("punishment-1")
            .addAddTicketIds("ticket-add-1")
            .build();

        performV3ModifyTickets("punishment-1", request)
            .andExpect(status().isOk());

        ArgumentCaptor<List<String>> addTicketIdsCaptor = listCaptor();
        ArgumentCaptor<List<String>> removeTicketIdsCaptor = listCaptor();
        ArgumentCaptor<Boolean> modifyAssociatedTicketsCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> issuerNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> issuerIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(punishmentMutationService).modifyPunishmentTickets(
            same(server),
            eq("punishment-1"),
            addTicketIdsCaptor.capture(),
            removeTicketIdsCaptor.capture(),
            modifyAssociatedTicketsCaptor.capture(),
            issuerNameCaptor.capture(),
            issuerIdCaptor.capture()
        );
        assertEquals(List.of("ticket-add-1"), addTicketIdsCaptor.getValue());
        assertEquals(List.of(), removeTicketIdsCaptor.getValue());
        assertFalse(modifyAssociatedTicketsCaptor.getValue());
        assertNull(issuerNameCaptor.getValue());
        assertNull(issuerIdCaptor.getValue());
    }

    @Test
    void v3ModifyTicketsOmittedListsReachServiceAsEmptyListsAndReturnSuccess() throws Exception {
        when(punishmentMutationService.modifyPunishmentTickets(same(server), any(), any(), any(), anyBoolean(), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment tickets modified", true, 1));

        ModifyPunishmentTicketsRequest request = ModifyPunishmentTicketsRequest.newBuilder()
            .setPunishmentId("punishment-1")
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setModifyAssociatedTickets(true)
            .build();

        MvcResult result = performV3ModifyTickets("punishment-1", request)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ModifyPunishmentTicketsResponse response =
            ModifyPunishmentTicketsResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.hasSuccess());
        assertTrue(response.getSuccess());

        ArgumentCaptor<List<String>> addTicketIdsCaptor = listCaptor();
        ArgumentCaptor<List<String>> removeTicketIdsCaptor = listCaptor();
        verify(punishmentMutationService).modifyPunishmentTickets(
            same(server),
            eq("punishment-1"),
            addTicketIdsCaptor.capture(),
            removeTicketIdsCaptor.capture(),
            anyBoolean(),
            any(),
            any()
        );
        assertEquals(List.of(), addTicketIdsCaptor.getValue());
        assertEquals(List.of(), removeTicketIdsCaptor.getValue());
    }

    @Test
    void v3ModifyTicketsValidationFailureReturnsBinaryApiErrorAndSkipsService() throws Exception {
        ModifyPunishmentTicketsRequest request = ModifyPunishmentTicketsRequest.newBuilder()
            .setPunishmentId("x".repeat(65))
            .setIssuerName("x".repeat(65))
            .setIssuerId("x".repeat(65))
            .addAllAddTicketIds(List.of("ticket-add-1", "x".repeat(65)))
            .addAllRemoveTicketIds(List.of("ticket-remove-1", "   "))
            .build();

        MvcResult result = performV3ModifyTickets("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("punishment_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_name")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("issuer_id")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("add_ticket_ids")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("remove_ticket_ids")));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v3ModifyTicketsRejectsTooManyAddAndRemoveIdsWithBinaryApiErrorAndSkipsService() throws Exception {
        ModifyPunishmentTicketsRequest request = ModifyPunishmentTicketsRequest.newBuilder()
            .setPunishmentId("punishment-1")
            .addAllAddTicketIds(numberedIds("add-ticket-", 51))
            .addAllRemoveTicketIds(numberedIds("remove-ticket-", 51))
            .build();

        MvcResult result = performV3ModifyTickets("punishment-1", request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("add_ticket_ids")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("remove_ticket_ids")));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v3ModifyTicketsRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/punishment-1/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v1PardonOversizedReasonStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS
                + "/punishment-1/pardon")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "issuerName": "Mod",
                      "issuerId": "issuer-1",
                      "reason": "%s",
                      "expectedType": "BAN"
                    }
                    """.formatted("x".repeat(501))))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentLifecycleService);
    }

    @Test
    void v1ModifyTicketsOversizedAddTicketIdsStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS
                + "/punishment-1/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "issuerName": "Mod",
                      "issuerId": "issuer-1",
                      "addTicketIds": [
                        "ticket-1", "ticket-2", "ticket-3", "ticket-4", "ticket-5",
                        "ticket-6", "ticket-7", "ticket-8", "ticket-9", "ticket-10",
                        "ticket-11", "ticket-12", "ticket-13", "ticket-14", "ticket-15",
                        "ticket-16", "ticket-17", "ticket-18", "ticket-19", "ticket-20",
                        "ticket-21", "ticket-22", "ticket-23", "ticket-24", "ticket-25",
                        "ticket-26", "ticket-27", "ticket-28", "ticket-29", "ticket-30",
                        "ticket-31", "ticket-32", "ticket-33", "ticket-34", "ticket-35",
                        "ticket-36", "ticket-37", "ticket-38", "ticket-39", "ticket-40",
                        "ticket-41", "ticket-42", "ticket-43", "ticket-44", "ticket-45",
                        "ticket-46", "ticket-47", "ticket-48", "ticket-49", "ticket-50",
                        "ticket-51"
                      ],
                      "removeTicketIds": [],
                      "modifyAssociatedTickets": true
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v1ToggleOptionInvalidBodyStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS
                + "/punishment-1/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "option": "   ",
                      "enabled": true
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v1AddNoteBlankNoteStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS
                + "/punishment-1/note")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "issuerName": "Mod",
                      "issuerId": "issuer-1",
                      "note": "   "
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentEvidenceService);
    }

    @Test
    void v1AddEvidenceBlankEvidenceUrlStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS
                + "/punishment-1/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "issuerName": "Mod",
                      "issuerId": "issuer-1",
                      "evidenceUrl": "   "
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentEvidenceService);
    }

    @Test
    void v1ChangeDurationInvalidNegativeDurationStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS
                + "/punishment-1/duration")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "issuerName": "Mod",
                      "issuerId": "issuer-1",
                      "newDuration": -1
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v1CreateUploadTokenOversizedIssuerNameStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS
                + "/punishment-1/upload-token")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "issuerName": "%s",
                      "issuerId": "issuer-1"
                    }
                    """.formatted("x".repeat(65))))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentQueryService);
    }

    @Test
    void v1StatWipeAcknowledgeInvalidBodyStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS
                + "/punishment-1/stat-wipe-acknowledge")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "punishmentId": "   ",
                      "success": true
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentMutationService);
    }

    @Test
    void v1AcknowledgeInvalidUuidStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_PUNISHMENTS + "/acknowledge")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "punishmentId": "punishment-1",
                      "playerUuid": "not-a-uuid",
                      "success": true
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verifyNoInteractions(punishmentLifecycleService);
    }

    private ResultActions performV3Acknowledge(
        PunishmentAcknowledgeRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/acknowledge")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3Preview(
        String playerUuid,
        int typeOrdinal
    ) throws Exception {
        return v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/preview")
            .queryParam("playerUuid", playerUuid)
            .queryParam("typeOrdinal", Integer.toString(typeOrdinal))
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF));
    }

    private ResultActions performV3Recent() throws Exception {
        return v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/recent")
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF));
    }

    private ResultActions performV3Recent(String hours) throws Exception {
        return v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/recent")
            .queryParam("hours", hours)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF));
    }

    private ResultActions performV3Detail(String punishmentId) throws Exception {
        return v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/" + punishmentId)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF));
    }

    private ResultActions performV3CreateDynamic(
        CreatePunishmentRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/dynamic")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3Create(
        CreatePunishmentRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/create")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3StatWipeAcknowledge(
        String pathPunishmentId,
        StatWipeAcknowledgeRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/" + pathPunishmentId + "/stat-wipe-acknowledge")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3ToggleOption(
        String pathPunishmentId,
        TogglePunishmentOptionRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/" + pathPunishmentId + "/toggle")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3ChangeDuration(
        String pathPunishmentId,
        ChangePunishmentDurationRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/" + pathPunishmentId + "/duration")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3AddNote(
        String pathPunishmentId,
        AddPunishmentNoteRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/" + pathPunishmentId + "/note")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3AddEvidence(
        String pathPunishmentId,
        AddPunishmentEvidenceRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/" + pathPunishmentId + "/evidence")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3CreateUploadToken(
        String pathPunishmentId,
        CreateEvidenceUploadTokenRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/" + pathPunishmentId + "/upload-token")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3ModifyTickets(
        String pathPunishmentId,
        ModifyPunishmentTicketsRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/" + pathPunishmentId + "/tickets")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private ResultActions performV3Pardon(
        String pathPunishmentId,
        PardonPunishmentRequest request
    ) throws Exception {
        return v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT
                + "/punishments/" + pathPunishmentId + "/pardon")
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .content(request.toByteArray()));
    }

    private PunishmentAcknowledgeRequest validRequest(String punishmentId) {
        return PunishmentAcknowledgeRequest.newBuilder()
            .setPunishmentId(punishmentId)
            .setPlayerUuid(PLAYER_UUID)
            .setExecutedAt("2026-05-02T20:00:00Z")
            .setSuccess(true)
            .build();
    }

    private StatWipeAcknowledgeRequest validStatWipeRequest(String punishmentId) {
        return StatWipeAcknowledgeRequest.newBuilder()
            .setPunishmentId(punishmentId)
            .setServerName("hub")
            .setSuccess(true)
            .build();
    }

    private TogglePunishmentOptionRequest validToggleRequest(String punishmentId) {
        return TogglePunishmentOptionRequest.newBuilder()
            .setPunishmentId(punishmentId)
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setOption("STAT_WIPE")
            .setEnabled(true)
            .build();
    }

    private ChangePunishmentDurationRequest validChangeDurationRequest(String punishmentId) {
        return ChangePunishmentDurationRequest.newBuilder()
            .setPunishmentId(punishmentId)
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setNewDuration(3600L)
            .build();
    }

    private AddPunishmentNoteRequest validAddNoteRequest(String punishmentId) {
        return AddPunishmentNoteRequest.newBuilder()
            .setPunishmentId(punishmentId)
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setNote("Needs review")
            .build();
    }

    private AddPunishmentEvidenceRequest validAddEvidenceRequest(String punishmentId) {
        return AddPunishmentEvidenceRequest.newBuilder()
            .setPunishmentId(punishmentId)
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setEvidenceUrl("https://example.com/evidence.png")
            .build();
    }

    private CreateEvidenceUploadTokenRequest validUploadTokenRequest() {
        return CreateEvidenceUploadTokenRequest.newBuilder()
            .setIssuerName("Mod")
            .build();
    }

    private ModifyPunishmentTicketsRequest validModifyTicketsRequest(String punishmentId) {
        return ModifyPunishmentTicketsRequest.newBuilder()
            .setPunishmentId(punishmentId)
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .addAddTicketIds("ticket-add-1")
            .addAddTicketIds("ticket-add-2")
            .addRemoveTicketIds("ticket-remove-1")
            .setModifyAssociatedTickets(true)
            .build();
    }

    private PardonPunishmentRequest validPardonRequest() {
        return PardonPunishmentRequest.newBuilder()
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setReason("Accepted appeal")
            .build();
    }

    private CreatePunishmentRequest validCreatePunishmentRequest() {
        return CreatePunishmentRequest.newBuilder()
            .setTargetUuid(PLAYER_UUID)
            .setIssuerName("Mod")
            .setIssuerId("issuer-1")
            .setTypeOrdinal(7)
            .setReason("Rule violation")
            .setDuration(3600L)
            .addNotes("note-1")
            .addNotes("note-2")
            .addAttachedTicketIds("ticket-1")
            .addAttachedTicketIds("ticket-2")
            .setSeverity("regular")
            .setStatus("Queued")
            .build();
    }

    private Map<String, Object> nestedStructMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("string", "value");
        data.put("number", 12.5);
        data.put("boolean", true);
        data.put("nullish", null);
        data.put("list", List.of("first", 2, false));
        data.put("nested", Map.of("child", "inside"));
        return data;
    }

    private void assertSeverityPreview(
        PunishmentPreviewResponse.SeverityPreview response,
        String severity,
        int points,
        long durationMs,
        String durationFormatted,
        String punishmentType,
        boolean permanent,
        String newSocialStatus,
        String newGameplayStatus,
        int newSocialPoints,
        int newGameplayPoints
    ) {
        assertEquals(severity, response.getSeverity());
        assertEquals(points, response.getPoints());
        assertEquals(durationMs, response.getDurationMs());
        assertEquals(durationFormatted, response.getDurationFormatted());
        assertEquals(punishmentType, response.getPunishmentType());
        assertEquals(permanent, response.getPermanent());
        assertEquals(newSocialStatus, response.getNewSocialStatus());
        assertEquals(newGameplayStatus, response.getNewGameplayStatus());
        assertEquals(newSocialPoints, response.getNewSocialPoints());
        assertEquals(newGameplayPoints, response.getNewGameplayPoints());
    }

    private void assertStructField(Struct struct, String field, String value) {
        assertEquals(value, struct.getFieldsOrThrow(field).getStringValue());
    }

    private void assertStructField(Struct struct, String field, boolean value) {
        assertEquals(value, struct.getFieldsOrThrow(field).getBoolValue());
    }

    private void assertStructNumberField(Struct struct, String field, double value) {
        assertEquals(value, struct.getFieldsOrThrow(field).getNumberValue());
    }

    private void assertNestedStructMap(Struct struct) {
        assertStructField(struct, "string", "value");
        assertStructNumberField(struct, "number", 12.5);
        assertStructField(struct, "boolean", true);
        assertEquals(NullValue.NULL_VALUE, struct.getFieldsOrThrow("nullish").getNullValue());
        assertEquals("first", struct.getFieldsOrThrow("list").getListValue().getValues(0).getStringValue());
        assertEquals(2.0, struct.getFieldsOrThrow("list").getListValue().getValues(1).getNumberValue());
        assertFalse(struct.getFieldsOrThrow("list").getListValue().getValues(2).getBoolValue());
        assertEquals("inside", struct.getFieldsOrThrow("nested")
            .getStructValue().getFieldsOrThrow("child").getStringValue());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<String>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private List<String> numberedIds(String prefix, int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> prefix + i)
            .toList();
    }
}
