package gg.modl.backend.player.controller.v2;

import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.player.controller.LegacyProtoJsonWriter;
import gg.modl.backend.player.service.MinecraftStartupService;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.StartupResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV2.PREFIX_MINECRAFT)
@RequiredArgsConstructor
@Slf4j
public class MinecraftStartupController {
    private final MinecraftStartupService minecraftStartupService;

    @PostMapping("/startup")
    public ResponseEntity<String> startup(
        @RequestBody @Valid StartupRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        String clientIp = RequestUtil.getClientIp(httpRequest);
        StartupResponse response = minecraftStartupService.handleStartup(server, request, clientIp);
        return LegacyProtoJsonWriter.ok(response);
    }

    public record StartupRequest(
        String serverVersion,
        String platformType,
        String pluginVersion,
        int maxPlayers,
        String serverName
    ) {
    }
}
