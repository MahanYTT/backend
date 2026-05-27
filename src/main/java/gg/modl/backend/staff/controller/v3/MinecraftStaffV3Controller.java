package gg.modl.backend.staff.controller.v3;

import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.dto.response.MinecraftStaffPermissionsResponse;
import gg.modl.backend.staff.dto.response.MinecraftStaffSummaryResponse;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.proto.modl.v1.MinecraftStaffOperationResponse;
import gg.modl.proto.modl.v1.StaffDisconnectRequest;
import gg.modl.proto.modl.v1.StaffListResponse;
import gg.modl.proto.modl.v1.StaffPermissionsListResponse;
import gg.modl.proto.modl.v1.UpdateStaffRoleRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/staff")
@RequiredArgsConstructor
public class MinecraftStaffV3Controller {
    private final StaffService staffService;

    @GetMapping(produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)
    public ResponseEntity<StaffListResponse> getAllStaff(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        List<MinecraftStaffSummaryResponse> staffList = staffService.getMinecraftStaffSummary(server);

        return ResponseEntity.ok(MinecraftStaffProtoMapper.toStaffListResponse(staffList));
    }

    @GetMapping(
        value = "/permissions",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<StaffPermissionsListResponse> getStaffPermissions(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        List<MinecraftStaffPermissionsResponse> staffList = staffService.getMinecraftStaffPermissions(server);

        return ResponseEntity.ok(MinecraftStaffProtoMapper.toStaffPermissionsListResponse(staffList));
    }

    @PatchMapping(
        value = "/{id}/role",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<MinecraftStaffOperationResponse> updateStaffRole(
        @PathVariable String id,
        @RequestBody @Valid UpdateStaffRoleRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);

        if (!staffService.updateMinecraftStaffRole(server, id, request.getRole())) {
            return operationResponse(HttpStatus.NOT_FOUND, false, "Staff member not found");
        }

        return operationResponse(HttpStatus.OK, true, null);
    }

    @PostMapping(
        value = "/disconnect",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<MinecraftStaffOperationResponse> staffDisconnect(
        @RequestBody @Valid StaffDisconnectRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);

        if (!staffService.markStaffDisconnected(server, request.getMinecraftUuid())) {
            return operationResponse(HttpStatus.NOT_FOUND, false, "Staff member not found");
        }

        return operationResponse(HttpStatus.OK, true, null);
    }

    private ResponseEntity<MinecraftStaffOperationResponse> operationResponse(HttpStatus httpStatus, boolean success, String message) {
        MinecraftStaffOperationResponse.Builder response = MinecraftStaffOperationResponse.newBuilder()
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
