package gg.modl.backend.player.controller.v1;

import gg.modl.backend.player.controller.LegacyProtoJsonWriter;
import gg.modl.backend.player.service.MinecraftSyncService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.proto.modl.v1.SyncResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_PLAYERS)
@RequiredArgsConstructor
@Slf4j
public class MinecraftSyncController {
    private final MinecraftSyncService minecraftSyncService;

    @PostMapping("/sync")
    public ResponseEntity<String> sync(
        @RequestBody @Valid SyncRequest syncRequest,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftSyncService.ServerStatusInput serverStatusInput = syncRequest.serverStatus() != null
            ? new MinecraftSyncService.ServerStatusInput(
                syncRequest.serverStatus().onlinePlayerCount(),
                syncRequest.serverStatus().maxPlayers(),
                syncRequest.serverStatus().serverVersion(),
                syncRequest.serverStatus().platformType(),
                syncRequest.serverStatus().pluginVersion()
            )
            : null;
        String clientIp = RequestUtil.getClientIp(httpRequest);
        SyncResponse response = minecraftSyncService.sync(
            server,
            syncRequest.lastSyncTimestamp(),
            syncRequest.onlinePlayers() == null ? List.of() : syncRequest.onlinePlayers()
                .stream()
                .map(player -> new MinecraftSyncService.OnlinePlayerInput(player.uuid(), player.username(), player.ipAddress()))
                .toList(),
            syncRequest.serverName(),
            syncRequest.chatLogs() == null ? List.of() : syncRequest.chatLogs()
                .stream()
                .map(log -> new MinecraftSyncService.ChatLogInput(log.uuid(), log.username(), log.message(), log.timestamp(), log.server()))
                .toList(),
            syncRequest.commandLogs() == null ? List.of() : syncRequest.commandLogs()
                .stream()
                .map(log -> new MinecraftSyncService.CommandLogInput(log.uuid(), log.username(), log.command(), log.timestamp(), log.server()))
                .toList(),
            serverStatusInput,
            clientIp
        );
        return LegacyProtoJsonWriter.ok(response);
    }

    //public record SyncRequest(
    //    @Size(max = RequestValidationLimits.TIMESTAMP_MAX_LENGTH) String lastSyncTimestamp,
    //    @Valid @Size(max = RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES) List<OnlinePlayer> onlinePlayers,
    //    @Valid ServerStatus serverStatus,
    //    @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String serverName,
    //    @Valid @Size(max = RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES) List<ChatLogEntry> chatLogs,
    //    @Valid @Size(max = RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES) List<CommandLogEntry> commandLogs
    //) {
    //}

    public record SyncRequest(
        String lastSyncTimestamp,
        List<OnlinePlayer> onlinePlayers,
        ServerStatus serverStatus,
        String serverName,
        List<ChatLogEntry> chatLogs,
        List<CommandLogEntry> commandLogs,
        String serverInstanceId
    ) {
    }

    public record ChatLogEntry(
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @NotBlank @Pattern(regexp = RegExpConstants.MINECRAFT_USERNAME) String username,
        @NotBlank @Size(max = RequestValidationLimits.CHAT_LOG_MESSAGE_MAX_LENGTH) String message,
        @PositiveOrZero long timestamp,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String server
    ) {
    }

    public record CommandLogEntry(
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @NotBlank @Pattern(regexp = RegExpConstants.MINECRAFT_USERNAME) String username,
        @NotBlank @Size(max = RequestValidationLimits.COMMAND_LOG_MAX_LENGTH) String command,
        @PositiveOrZero long timestamp,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String server
    ) {
    }

    public record OnlinePlayer(
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String uuid,
        @NotBlank @Pattern(regexp = RegExpConstants.MINECRAFT_USERNAME) String username,
        @Pattern(regexp = RegExpConstants.IP) String ipAddress
    ) {
    }

    public record ServerStatus(
        @Min(0) int onlinePlayerCount,
        @Min(0) int maxPlayers,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String serverVersion,
        @Size(max = RequestValidationLimits.TIMESTAMP_MAX_LENGTH) String timestamp,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String platformType,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) String pluginVersion
    ) {
    }
}
