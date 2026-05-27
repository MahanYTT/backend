package gg.modl.backend.player.controller.v3;

import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.player.service.MinecraftStartupService;
import gg.modl.backend.player.service.MinecraftSyncService;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.StartupRequest;
import gg.modl.proto.modl.v1.StartupResponse;
import gg.modl.proto.modl.v1.SyncChatLogEntry;
import gg.modl.proto.modl.v1.SyncCommandLogEntry;
import gg.modl.proto.modl.v1.SyncOnlinePlayer;
import gg.modl.proto.modl.v1.SyncRequest;
import gg.modl.proto.modl.v1.SyncResponse;
import gg.modl.proto.modl.v1.SyncServerStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT)
@RequiredArgsConstructor
public class MinecraftSyncV3Controller {
    private final MinecraftSyncService minecraftSyncService;
    private final MinecraftStartupService minecraftStartupService;

    @PostMapping(
        value = "/startup",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<StartupResponse> startup(
        @RequestBody @Valid StartupRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        String clientIp = RequestUtil.getClientIp(httpRequest);
        return ResponseEntity.ok(minecraftStartupService.handleStartup(
            server,
            request.getServerVersion(),
            request.getPlatformType(),
            request.getPluginVersion(),
            request.getMaxPlayers(),
            request.hasServerName() ? request.getServerName() : null,
            clientIp
        ));
    }

    @PostMapping(
        value = "/players/sync",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SyncResponse> sync(
        @RequestBody @Valid SyncRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        String clientIp = RequestUtil.getClientIp(httpRequest);
        return ResponseEntity.ok(minecraftSyncService.sync(
            server,
            request.getLastSyncTimestamp(),
            toOnlinePlayers(request),
            request.hasServerName() ? request.getServerName() : null,
            toChatLogs(request),
            toCommandLogs(request),
            toServerStatus(request),
            clientIp
        ));
    }

    private static List<MinecraftSyncService.OnlinePlayerInput> toOnlinePlayers(SyncRequest request) {
        return request.getOnlinePlayersList().stream()
            .map(MinecraftSyncV3Controller::toOnlinePlayer)
            .toList();
    }

    private static MinecraftSyncService.OnlinePlayerInput toOnlinePlayer(SyncOnlinePlayer player) {
        return new MinecraftSyncService.OnlinePlayerInput(
            player.getUuid(),
            player.getUsername(),
            player.getIpAddress()
        );
    }

    private static List<MinecraftSyncService.ChatLogInput> toChatLogs(SyncRequest request) {
        return request.getChatLogsList().stream()
            .map(MinecraftSyncV3Controller::toChatLog)
            .toList();
    }

    private static MinecraftSyncService.ChatLogInput toChatLog(SyncChatLogEntry log) {
        return new MinecraftSyncService.ChatLogInput(
            log.getUuid(),
            log.getUsername(),
            log.getMessage(),
            log.getTimestamp(),
            log.getServer()
        );
    }

    private static List<MinecraftSyncService.CommandLogInput> toCommandLogs(SyncRequest request) {
        return request.getCommandLogsList().stream()
            .map(MinecraftSyncV3Controller::toCommandLog)
            .toList();
    }

    private static MinecraftSyncService.CommandLogInput toCommandLog(SyncCommandLogEntry log) {
        return new MinecraftSyncService.CommandLogInput(
            log.getUuid(),
            log.getUsername(),
            log.getCommand(),
            log.getTimestamp(),
            log.getServer()
        );
    }

    private static MinecraftSyncService.ServerStatusInput toServerStatus(SyncRequest request) {
        if (!request.hasServerStatus()) {
            return null;
        }
        SyncServerStatus status = request.getServerStatus();
        return new MinecraftSyncService.ServerStatusInput(
            status.getOnlinePlayerCount(),
            status.getMaxPlayers(),
            status.getServerVersion(),
            status.getPlatformType(),
            status.getPluginVersion()
        );
    }
}
