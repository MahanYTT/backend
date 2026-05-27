package gg.modl.backend.player.controller.v2;

import gg.modl.backend.player.controller.LegacyProtoJsonWriter;
import gg.modl.backend.player.controller.v1.MinecraftSyncController.ChatLogEntry;
import gg.modl.backend.player.controller.v1.MinecraftSyncController.CommandLogEntry;
import gg.modl.backend.player.controller.v1.MinecraftSyncController.OnlinePlayer;
import gg.modl.backend.player.service.MinecraftSyncService;
import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.SyncResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV2.MINECRAFT_PLAYERS)
@RequiredArgsConstructor
@Slf4j
public class MinecraftSyncV2Controller {
    private final MinecraftSyncService minecraftSyncService;

    @PostMapping("/sync")
    public ResponseEntity<String> sync(
        @RequestBody @Valid V2SyncRequest syncRequest,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        String clientIp = RequestUtil.getClientIp(httpRequest);
        SyncResponse response = minecraftSyncService.syncV2(
            server,
            syncRequest.lastSyncTimestamp(),
            syncRequest.onlinePlayers() == null ? List.of() : syncRequest.onlinePlayers()
                .stream()
                .map(player -> new MinecraftSyncService.OnlinePlayerInput(player.uuid(), player.username(), player.ipAddress()))
                .toList(),
            syncRequest.serverName(),
            syncRequest.chatLogs() == null ? List.of() : syncRequest.chatLogs()
                .stream()
                .map(entry -> new MinecraftSyncService.ChatLogInput(entry.uuid(), entry.username(), entry.message(), entry.timestamp(), entry.server()))
                .toList(),
            syncRequest.commandLogs() == null ? List.of() : syncRequest.commandLogs()
                .stream()
                .map(entry -> new MinecraftSyncService.CommandLogInput(entry.uuid(), entry.username(), entry.command(), entry.timestamp(), entry.server()))
                .toList(),
            clientIp
        );
        return LegacyProtoJsonWriter.ok(response);
    }

    public record V2SyncRequest(
        String lastSyncTimestamp,
        List<OnlinePlayer> onlinePlayers,
        String serverName,
        List<ChatLogEntry> chatLogs,
        List<CommandLogEntry> commandLogs,
        String serverInstanceId
    ) {
    }
}
