package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.controller.v1.MinecraftPunishmentTypeController;
import gg.modl.backend.settings.controller.v3.MinecraftPunishmentTypeV3Controller;
import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.OffenseLevelDurations;
import gg.modl.backend.settings.data.PunishmentDurations;
import gg.modl.backend.settings.data.PunishmentPoints;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.proto.modl.v1.PunishmentTypesResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftPunishmentTypeV3ControllerTest {
    @Mock private PunishmentTypeService punishmentTypeService;
    private MockMvc v3MockMvc;
    private MockMvc v1MockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        v3MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftPunishmentTypeV3Controller(punishmentTypeService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();

        v1MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftPunishmentTypeController(punishmentTypeService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3PunishmentTypesReturnsBinaryResponseMappedFromService() throws Exception {
        PunishmentType punishmentType = PunishmentType.builder()
            .id(12)
            .ordinal(7)
            .name("Chat Abuse")
            .category("Social")
            .staffDescription("Staff text")
            .playerDescription("Player text")
            .customizable(true)
            .durations(new PunishmentDurations(
                offenseDurations(new DurationDetail(30, "minutes", "mute")),
                offenseDurations(new DurationDetail(2, "hours", "mute")),
                offenseDurations(new DurationDetail(1, "days", "mute"))
            ))
            .points(new PunishmentPoints(1, 2, 3))
            .customPoints(5)
            .canBeAltBlocking(true)
            .canBeStatWiping(false)
            .singleSeverityPunishment(true)
            .permanentUntilSkinChange(false)
            .permanentUntilUsernameChange(true)
            .build();
        when(punishmentTypeService.getPunishmentTypes(server)).thenReturn(List.of(punishmentType));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/punishments/types")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        PunishmentTypesResponse response = PunishmentTypesResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(1, response.getDataCount());
        PunishmentTypesResponse.PunishmentTypeData type = response.getData(0);
        assertEquals("Chat Abuse", type.getName());
        assertEquals("Social", type.getCategory());
        assertEquals("Staff text", type.getStaffDescription());
        assertEquals("Player text", type.getPlayerDescription());
        assertEquals(12, type.getId());
        assertEquals(7, type.getOrdinal());
        assertTrue(type.getIsCustomizable());
        assertEquals(5, type.getCustomPoints());
        assertTrue(type.getCanBeAltBlocking());
        assertFalse(type.getCanBeStatWiping());
        assertTrue(type.getSingleSeverityPunishment());
        assertFalse(type.getPermanentUntilSkinChange());
        assertTrue(type.getPermanentUntilUsernameChange());
        assertEquals(2, type.getPoints().getFieldsOrThrow("regular").getNumberValue());
        assertEquals("hours", type.getDurations()
            .getFieldsOrThrow("regular")
            .getStructValue()
            .getFieldsOrThrow("first")
            .getStructValue()
            .getFieldsOrThrow("unit")
            .getStringValue());
        verify(punishmentTypeService).getPunishmentTypes(same(server));
    }

    @Test
    void v1PunishmentTypesStillReturnsJsonResponse() throws Exception {
        when(punishmentTypeService.getPunishmentTypes(server)).thenReturn(List.of(PunishmentType.builder()
            .id(1)
            .ordinal(0)
            .name("Kick")
            .category("Administrative")
            .build()));

        MvcResult result = v1MockMvc.perform(get(RESTMappingV1.MINECRAFT_PUNISHMENTS + "/types")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertEquals(200, json.get("status").asInt());
        assertEquals("Kick", json.get("data").get(0).get("name").asText());
    }

    private static OffenseLevelDurations offenseDurations(DurationDetail detail) {
        return new OffenseLevelDurations(detail, detail, detail);
    }
}
