package gg.modl.backend.player.controller.v1;

import gg.modl.backend.player.dto.request.AcknowledgeNotificationsRequest;
import gg.modl.backend.player.service.MinecraftPlayerService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_NOTIFICATIONS)
@RequiredArgsConstructor
public class MinecraftNotificationController {
    private final MinecraftPlayerService minecraftPlayerService;

    @PostMapping("/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeNotifications(
        @RequestBody @Valid AcknowledgeNotificationsRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftPlayerService.ServiceResponse response = minecraftPlayerService.acknowledgeNotifications(server, request);
        return ResponseEntity.status(response.status()).body(response.body());
    }
}