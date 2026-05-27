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
import gg.modl.backend.role.controller.v1.MinecraftRolesController;
import gg.modl.backend.role.controller.v3.MinecraftRolesV3Controller;
import gg.modl.backend.role.dto.response.RoleResponse;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.MinecraftRoleDetailResponse;
import gg.modl.proto.modl.v1.MinecraftRoleListResponse;
import gg.modl.proto.modl.v1.MinecraftRoleMutationResponse;
import gg.modl.proto.modl.v1.UpdateRolePermissionsRequest;
import java.util.Date;
import java.util.List;
import java.util.Optional;
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
class MinecraftRolesV3ControllerTest {
    @Mock private RoleService roleService;
    private MockMvc v3MockMvc;
    private MockMvc v1MockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        v3MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftRolesV3Controller(roleService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();

        v1MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftRolesController(roleService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3RoleListReturnsBinaryResponseMappedFromRoleService() throws Exception {
        when(roleService.getAllRoles(server)).thenReturn(List.of(
            new RoleResponse(
                "admin",
                "Admin",
                "Full access",
                List.of("modl.roles.view", "modl.roles.edit"),
                true,
                1,
                3,
                new Date(1_700_000_000_123L),
                new Date(1_700_100_000_456L)
            ),
            new RoleResponse(
                "helper",
                "Helper",
                null,
                null,
                false,
                2,
                0,
                null,
                null
            )
        ));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/roles")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftRoleListResponse response = MinecraftRoleListResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(2, response.getRolesCount());
        assertEquals("admin", response.getRoles(0).getId());
        assertEquals("Admin", response.getRoles(0).getName());
        assertEquals("Full access", response.getRoles(0).getDescription());
        assertEquals(List.of("modl.roles.view", "modl.roles.edit"), response.getRoles(0).getPermissionsList());
        assertTrue(response.getRoles(0).getIsDefault());
        assertEquals(1, response.getRoles(0).getOrder());
        assertEquals(1_700_000_000L, response.getRoles(0).getCreatedAt().getSeconds());
        assertEquals(123_000_000, response.getRoles(0).getCreatedAt().getNanos());
        assertEquals(1_700_100_000L, response.getRoles(0).getUpdatedAt().getSeconds());
        assertEquals(456_000_000, response.getRoles(0).getUpdatedAt().getNanos());
        assertEquals("", response.getRoles(1).getDescription());
        assertEquals(0, response.getRoles(1).getPermissionsCount());
        assertFalse(response.getRoles(1).getIsDefault());
        assertFalse(response.getRoles(1).hasCreatedAt());
        assertFalse(response.getRoles(1).hasUpdatedAt());
        verify(roleService).getAllRoles(same(server));
    }

    @Test
    void v3RoleDetailReturnsBinaryResponseMappedFromRoleService() throws Exception {
        when(roleService.getRoleById(server, "admin")).thenReturn(Optional.of(new RoleResponse(
            "admin",
            "Admin",
            "Full access",
            List.of("first", "second"),
            true,
            1,
            3,
            new Date(1_700_000_000_000L),
            new Date(1_700_100_000_000L)
        )));

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/roles/admin")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftRoleDetailResponse response = MinecraftRoleDetailResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("admin", response.getRole().getId());
        assertEquals("Admin", response.getRole().getName());
        assertEquals("Full access", response.getRole().getDescription());
        assertEquals(List.of("first", "second"), response.getRole().getPermissionsList());
        assertTrue(response.getRole().getIsDefault());
        assertEquals(1, response.getRole().getOrder());
        assertEquals(1_700_000_000L, response.getRole().getCreatedAt().getSeconds());
        assertEquals(1_700_100_000L, response.getRole().getUpdatedAt().getSeconds());
        verify(roleService).getRoleById(same(server), eq("admin"));
    }

    @Test
    void v3RoleDetailNotFoundReturnsBinaryApiError() throws Exception {
        when(roleService.getRoleById(server, "missing")).thenReturn(Optional.empty());

        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/roles/missing")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_FOUND", error.getCode());
        assertEquals("Role not found", error.getMessage());
    }

    @Test
    void v3UpdateRolePermissionsCallsServiceAndReturnsBinarySuccess() throws Exception {
        when(roleService.updateRolePermissions(server, "admin", List.of("first", "second"))).thenReturn(true);
        UpdateRolePermissionsRequest request = UpdateRolePermissionsRequest.newBuilder()
            .addAllPermissions(List.of("first", "second"))
            .build();

        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/roles/admin/permissions")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftRoleMutationResponse response = MinecraftRoleMutationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("", response.getMessage());
        verify(roleService).updateRolePermissions(same(server), eq("admin"), eq(List.of("first", "second")));
    }

    @Test
    void v3UpdateRolePermissionsMissingRoleReturnsBinaryNotFound() throws Exception {
        when(roleService.updateRolePermissions(server, "missing", List.of("first"))).thenReturn(false);
        UpdateRolePermissionsRequest request = UpdateRolePermissionsRequest.newBuilder()
            .addPermissions("first")
            .build();

        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/roles/missing/permissions")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftRoleMutationResponse response = MinecraftRoleMutationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(404, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Role not found", response.getMessage());
        verify(roleService).updateRolePermissions(same(server), eq("missing"), eq(List.of("first")));
    }

    @Test
    void v3UpdateRolePermissionsEmptyPermissionListCallsServiceAndReturnsBinarySuccess() throws Exception {
        when(roleService.updateRolePermissions(server, "admin", List.of())).thenReturn(true);
        UpdateRolePermissionsRequest request = UpdateRolePermissionsRequest.newBuilder().build();

        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/roles/admin/permissions")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftRoleMutationResponse response = MinecraftRoleMutationResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("", response.getMessage());
        verify(roleService).updateRolePermissions(same(server), eq("admin"), eq(List.of()));
    }

    @Test
    void v3UpdateRolePermissionsRejectsWhitespacePermissionWithBinaryApiError() throws Exception {
        UpdateRolePermissionsRequest request = UpdateRolePermissionsRequest.newBuilder()
            .addPermissions("   ")
            .build();

        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/roles/admin/permissions")
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
            .anyMatch(violation -> violation.getField().contains("permissions")));
    }

    @Test
    void v3UpdateRolePermissionsRejectsOversizedPermissionWithBinaryApiError() throws Exception {
        UpdateRolePermissionsRequest request = UpdateRolePermissionsRequest.newBuilder()
            .addPermissions("a".repeat(129))
            .build();

        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/roles/admin/permissions")
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
            .anyMatch(violation -> violation.getField().contains("permissions")));
    }

    @Test
    void v3UpdateRolePermissionsRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(patch(RESTMappingV3.PREFIX_MINECRAFT + "/roles/admin/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{\"permissions\":[\"first\"]}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
    }

    @Test
    void v3RoleListRejectsJsonAcceptWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/roles")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
    }

    @Test
    void v1RoleListStillReturnsJsonEnvelope() throws Exception {
        when(roleService.getAllRoles(server)).thenReturn(List.of(new RoleResponse(
            "admin",
            "Admin",
            "Full access",
            List.of("modl.roles.view"),
            true,
            1,
            3,
            new Date(1_700_000_000_000L),
            new Date(1_700_100_000_000L)
        )));

        MvcResult result = v1MockMvc.perform(get(RESTMappingV1.MINECRAFT_ROLES)
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("status").asInt());
        assertEquals("admin", json.get("roles").get(0).get("id").asText());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
    }

    @Test
    void v1RoleDetailStillReturnsJsonEnvelope() throws Exception {
        when(roleService.getRoleById(server, "admin")).thenReturn(Optional.of(new RoleResponse(
            "admin",
            "Admin",
            "Full access",
            List.of("modl.roles.view"),
            true,
            1,
            3,
            new Date(1_700_000_000_000L),
            new Date(1_700_100_000_000L)
        )));

        MvcResult result = v1MockMvc.perform(get(RESTMappingV1.MINECRAFT_ROLES + "/admin")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("status").asInt());
        assertEquals("admin", json.get("role").get("id").asText());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
    }

    @Test
    void v1UpdateRolePermissionsStillReturnsJsonEnvelope() throws Exception {
        when(roleService.updateRolePermissions(server, "admin", List.of("first"))).thenReturn(true);

        MvcResult result = v1MockMvc.perform(patch(RESTMappingV1.MINECRAFT_ROLES + "/admin/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {"permissions":["first"]}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        assertEquals(200, json.get("status").asInt());
        assertTrue(json.get("success").asBoolean());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
        verify(roleService).updateRolePermissions(server, "admin", List.of("first"));
    }
}
