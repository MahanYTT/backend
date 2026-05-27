package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.staff.controller.v1.MinecraftStaffController;
import gg.modl.backend.staff.controller.v3.MinecraftStaffV3Controller;
import gg.modl.backend.staff.dto.response.MinecraftStaffPermissionsResponse;
import gg.modl.backend.staff.dto.response.MinecraftStaffSummaryResponse;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.MinecraftStaffOperationResponse;
import gg.modl.proto.modl.v1.StaffDisconnectRequest;
import gg.modl.proto.modl.v1.StaffListResponse;
import gg.modl.proto.modl.v1.StaffPermissionsListResponse;
import gg.modl.proto.modl.v1.UpdateStaffRoleRequest;
import java.util.Date;
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
class MinecraftStaffV3ControllerTest {
    @Mock private StaffService staffService;
    private MockMvc v3MockMvc;
    private MockMvc v1MockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        v3MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftStaffV3Controller(staffService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();

        v1MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftStaffController(staffService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3StaffListReturnsBinaryResponseMappedFromStaffService() throws Exception {
        when(staffService.getMinecraftStaffSummary(server)).thenReturn(List.of(
            new MinecraftStaffSummaryResponse(
                "staff-1",
                "Moderator",
                "mod@example.com",
                "Admin",
                "11111111-2222-3333-4444-555555555555",
                "ModMc",
                List.of("modl.staff.view", "modl.staff.edit"),
                new Date(1_700_000_000_000L),
                123_456L,
                "survival",
                7,
                new Date(1_600_000_000_000L),
                new Date(1_700_100_000_000L)
            ),
            new MinecraftStaffSummaryResponse(
                "staff-2",
                "Helper",
                "helper@example.com",
                "Helper",
                "22222222-3333-4444-5555-666666666666",
                "HelperMc",
                null,
                null,
                0L,
                null,
                0,
                null,
                null
            )
        ));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/staff")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        StaffListResponse response = StaffListResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(2, response.getStaffCount());
        assertEquals("staff-1", response.getStaff(0).getId());
        assertEquals("Moderator", response.getStaff(0).getUsername());
        assertEquals("mod@example.com", response.getStaff(0).getEmail());
        assertEquals("Admin", response.getStaff(0).getRole());
        assertEquals("11111111-2222-3333-4444-555555555555", response.getStaff(0).getMinecraftUuid());
        assertEquals("ModMc", response.getStaff(0).getMinecraftUsername());
        assertEquals(List.of("modl.staff.view", "modl.staff.edit"), response.getStaff(0).getPermissionsList());
        assertEquals(1_700_000_000_000L, response.getStaff(0).getLastSeen());
        assertEquals(123_456L, response.getStaff(0).getTotalPlaytimeMs());
        assertEquals("survival", response.getStaff(0).getLastServer());
        assertEquals(7, response.getStaff(0).getPunishmentsIssuedCount());
        assertEquals(1_600_000_000_000L, response.getStaff(0).getCreatedAt());
        assertEquals(1_700_100_000_000L, response.getStaff(0).getUpdatedAt());
        assertEquals(0, response.getStaff(1).getPermissionsCount());
        assertEquals(0L, response.getStaff(1).getLastSeen());
        assertEquals("", response.getStaff(1).getLastServer());
        verify(staffService).getMinecraftStaffSummary(same(server));
    }

    @Test
    void v3StaffPermissionsReturnsBinaryResponseMappedFromStaffService() throws Exception {
        when(staffService.getMinecraftStaffPermissions(server)).thenReturn(List.of(
            new MinecraftStaffPermissionsResponse(
                "11111111-2222-3333-4444-555555555555",
                "ModMc",
                "Moderator",
                "staff-1",
                "Admin",
                List.of("first", "second"),
                "mod@example.com"
            )
        ));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/staff/permissions")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        StaffPermissionsListResponse response = StaffPermissionsListResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(1, response.getData().getStaffCount());
        assertEquals("11111111-2222-3333-4444-555555555555", response.getData().getStaff(0).getMinecraftUuid());
        assertEquals("ModMc", response.getData().getStaff(0).getMinecraftUsername());
        assertEquals("Moderator", response.getData().getStaff(0).getStaffUsername());
        assertEquals("staff-1", response.getData().getStaff(0).getStaffId());
        assertEquals("Admin", response.getData().getStaff(0).getStaffRole());
        assertEquals(List.of("first", "second"), response.getData().getStaff(0).getPermissionsList());
        assertEquals("mod@example.com", response.getData().getStaff(0).getEmail());
        verify(staffService).getMinecraftStaffPermissions(same(server));
    }

    @Test
    void v3UpdateStaffRoleCallsServiceAndReturnsBinarySuccess() throws Exception {
        when(staffService.updateMinecraftStaffRole(server, "staff-1", "Admin")).thenReturn(true);
        UpdateStaffRoleRequest request = UpdateStaffRoleRequest.newBuilder()
            .setRole("Admin")
            .build();

        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/staff/staff-1/role")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftStaffOperationResponse response = MinecraftStaffOperationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertFalse(response.hasMessage());
        verify(staffService).updateMinecraftStaffRole(same(server), eq("staff-1"), eq("Admin"));
    }

    @Test
    void v3UpdateStaffRoleMissingStaffReturnsBinaryNotFound() throws Exception {
        when(staffService.updateMinecraftStaffRole(server, "missing-staff", "Admin")).thenReturn(false);
        UpdateStaffRoleRequest request = UpdateStaffRoleRequest.newBuilder()
            .setRole("Admin")
            .build();

        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/staff/missing-staff/role")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftStaffOperationResponse response = MinecraftStaffOperationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Staff member not found", response.getMessage());
        verify(staffService).updateMinecraftStaffRole(same(server), eq("missing-staff"), eq("Admin"));
    }

    @Test
    void v3UpdateStaffRoleUnknownRoleReturnsBinaryApiError() throws Exception {
        when(staffService.updateMinecraftStaffRole(server, "staff-1", "MissingRole"))
            .thenThrow(new ResourceNotFoundException("Role not found"));
        UpdateStaffRoleRequest request = UpdateStaffRoleRequest.newBuilder()
            .setRole("MissingRole")
            .build();

        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/staff/staff-1/role")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_FOUND", error.getCode());
        assertEquals("Role not found", error.getMessage());
        verify(staffService).updateMinecraftStaffRole(same(server), eq("staff-1"), eq("MissingRole"));
    }

    @Test
    void v3UpdateStaffRoleWhitespaceRoleReturnsBinaryApiError() throws Exception {
        UpdateStaffRoleRequest request = UpdateStaffRoleRequest.newBuilder()
            .setRole("   ")
            .build();

        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/staff/staff-1/role")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("role")));
    }

    @Test
    void v3StaffDisconnectMapsMinecraftUuidIntoServiceAndReturnsBinaryResponse() throws Exception {
        when(staffService.markStaffDisconnected(server, "11111111-2222-3333-4444-555555555555")).thenReturn(true);

        StaffDisconnectRequest request = StaffDisconnectRequest.newBuilder()
            .setMinecraftUuid("11111111-2222-3333-4444-555555555555")
            .setSessionDurationMs(123_456L)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/staff/disconnect")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftStaffOperationResponse response = MinecraftStaffOperationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertFalse(response.hasMessage());
        verify(staffService).markStaffDisconnected(same(server), eq("11111111-2222-3333-4444-555555555555"));
    }

    @Test
    void v3StaffDisconnectNotFoundReturnsBinaryOperationResponse() throws Exception {
        when(staffService.markStaffDisconnected(server, "11111111-2222-3333-4444-555555555555")).thenReturn(false);

        StaffDisconnectRequest request = StaffDisconnectRequest.newBuilder()
            .setMinecraftUuid("11111111-2222-3333-4444-555555555555")
            .setSessionDurationMs(123_456L)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/staff/disconnect")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftStaffOperationResponse response = MinecraftStaffOperationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Staff member not found", response.getMessage());
    }

    @Test
    void v3StaffDisconnectValidationRejectsInvalidUuidAndNegativeSessionDuration() throws Exception {
        StaffDisconnectRequest request = StaffDisconnectRequest.newBuilder()
            .setMinecraftUuid("not-a-uuid")
            .setSessionDurationMs(-1L)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/staff/disconnect")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("minecraft_uuid")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("session_duration_ms")));
    }

    @Test
    void v3UpdateStaffRoleRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/staff/staff-1/role")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{\"role\":\"Admin\"}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
    }

    @Test
    void v3StaffListRejectsJsonAcceptWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/staff")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
    }

    @Test
    void v1UpdateStaffRoleStillReturnsJsonEnvelope() throws Exception {
        when(staffService.updateMinecraftStaffRole(server, "staff-1", "Admin")).thenReturn(true);

        MvcResult result = v1MockMvc.perform(patch(RESTMappingV1.MINECRAFT_STAFF + "/staff-1/role")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {"role":"Admin"}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("status").asInt());
        assertTrue(json.get("success").asBoolean());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verify(staffService).updateMinecraftStaffRole(server, "staff-1", "Admin");
    }

    @Test
    void v1StaffListStillReturnsJsonEnvelope() throws Exception {
        when(staffService.getMinecraftStaffSummary(server)).thenReturn(List.of(
            new MinecraftStaffSummaryResponse(
                "staff-1",
                "Moderator",
                "mod@example.com",
                "Admin",
                "11111111-2222-3333-4444-555555555555",
                "ModMc",
                List.of("modl.staff.view"),
                new Date(1_700_000_000_000L),
                123_456L,
                "survival",
                7,
                new Date(1_600_000_000_000L),
                new Date(1_700_100_000_000L)
            )
        ));

        MvcResult result = v1MockMvc.perform(get(RESTMappingV1.MINECRAFT_STAFF)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("status").asInt());
        assertEquals("Moderator", json.get("staff").get(0).get("username").asText());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
    }
}
