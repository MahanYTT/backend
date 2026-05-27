package gg.modl.backend.player.controller.v3;

import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.player.service.MinecraftPlayerService;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.AcknowledgeNotificationsRequest;
import gg.modl.proto.modl.v1.SimpleResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/notifications")
@RequiredArgsConstructor
public class MinecraftNotificationV3Controller {
    private final MinecraftPlayerService minecraftPlayerService;

    @PostMapping(
        value = "/acknowledge",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SimpleResponse> acknowledgeNotifications(
        @RequestBody @Valid AcknowledgeNotificationsRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.acknowledgeNotifications(
            server,
            request.getPlayerUuid(),
            request.getNotificationIdsList(),
            request.getAcknowledgedAt().isEmpty() ? null : request.getAcknowledgedAt()
        );

        return ResponseEntity.status(response.status())
            .body(MinecraftPlayerProtoMapper.toSimpleResponse(response.body()));
    }
}
