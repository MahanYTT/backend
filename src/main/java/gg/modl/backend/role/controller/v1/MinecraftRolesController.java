package gg.modl.backend.role.controller.v1;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.role.dto.response.RoleResponse;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
@RequestMapping(RESTMappingV1.MINECRAFT_ROLES)
@RequiredArgsConstructor
public class MinecraftRolesController {
    private final RoleService roleService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllRoles(HttpServletRequest httpRequest) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        List<Map<String, Object>> roleList = roleService.getAllRoles(server)
            .stream()
            .map(this::toRoleMap)
            .toList();

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "roles", roleList
        ));
    }

    private Map<String, Object> toRoleMap(RoleResponse role) {
        Map<String, Object> roleData = new LinkedHashMap<>();
        roleData.put("id", role.id());
        roleData.put("name", role.name());
        roleData.put("description", role.description());
        roleData.put("permissions", role.permissions());
        roleData.put("isDefault", role.isDefault());
        roleData.put("order", role.order());
        roleData.put("createdAt", role.createdAt());
        roleData.put("updatedAt", role.updatedAt());
        return roleData;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getRole(
        @PathVariable String id,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        RoleResponse role = roleService.getRoleById(server, id).orElse(null);

        if (role == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", "Role not found"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "role", toRoleMap(role)
        ));
    }

    @PatchMapping("/{id}/permissions")
    public ResponseEntity<Map<String, Object>> updateRolePermissions(
        @PathVariable String id,
        @RequestBody @Valid UpdatePermissionsRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);

        if (!roleService.updateRolePermissions(server, id, request.permissions())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", "Role not found"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "success", true
        ));
    }

    public record UpdatePermissionsRequest(
        @NotNull @Size(max = RequestValidationLimits.ROLE_PERMISSIONS_MAX_ENTRIES) List<@NotBlank @Size(max = RequestValidationLimits.ROLE_PERMISSION_MAX_LENGTH) String> permissions
    ) {}
}
