package gg.modl.backend.role.controller.v3;

import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.dto.response.RoleResponse;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.MinecraftRoleDetailResponse;
import gg.modl.proto.modl.v1.MinecraftRoleListResponse;
import gg.modl.proto.modl.v1.MinecraftRoleMutationResponse;
import gg.modl.proto.modl.v1.UpdateRolePermissionsRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/roles")
@RequiredArgsConstructor
public class MinecraftRolesV3Controller {
    private final RoleService roleService;

    @GetMapping(produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)
    public ResponseEntity<MinecraftRoleListResponse> getAllRoles(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        List<RoleResponse> roles = roleService.getAllRoles(server);

        return ResponseEntity.ok(MinecraftRolesProtoMapper.toRoleListResponse(roles));
    }

    @GetMapping(
        value = "/{id}",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<?> getRole(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        return roleService.getRoleById(server, id)
            .<ResponseEntity<?>>map(role -> ResponseEntity.ok(MinecraftRolesProtoMapper.toRoleDetailResponse(role)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .body(ApiError.newBuilder()
                    .setStatusCode(404)
                    .setCode("NOT_FOUND")
                    .setMessage("Role not found")
                    .build()));
    }

    @PatchMapping(
        value = "/{id}/permissions",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<MinecraftRoleMutationResponse> updateRolePermissions(
        @PathVariable String id,
        @RequestBody(required = false) @Valid UpdateRolePermissionsRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        List<String> permissions = request != null ? request.getPermissionsList() : List.of();

        if (!roleService.updateRolePermissions(server, id, permissions)) {
            return mutationResponse(HttpStatus.NOT_FOUND, false, "Role not found");
        }

        return mutationResponse(HttpStatus.OK, true, null);
    }

    private ResponseEntity<MinecraftRoleMutationResponse> mutationResponse(
        HttpStatus httpStatus,
        boolean success,
        String message
    ) {
        MinecraftRoleMutationResponse.Builder response = MinecraftRoleMutationResponse.newBuilder()
            .setStatus(httpStatus.value())
            .setSuccess(success);
        if (message != null) {
            response.setMessage(message);
        }

        return ResponseEntity.status(httpStatus)
            .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
            .body(response.build());
    }
}
