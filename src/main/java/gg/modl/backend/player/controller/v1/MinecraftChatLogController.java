package gg.modl.backend.player.controller.v1;

import gg.modl.backend.player.dto.request.ChatLogBatchRequest;
import gg.modl.backend.player.dto.request.ChatLogEntryRequest;
import gg.modl.backend.player.dto.request.CommandLogBatchRequest;
import gg.modl.backend.player.dto.request.CommandLogEntryRequest;
import gg.modl.backend.player.service.MinecraftChatLogService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_PLAYERS)
@RequiredArgsConstructor
@Slf4j
@Validated
public class MinecraftChatLogController {

    private final MinecraftChatLogService minecraftChatLogService;

    @PostMapping("/chat-log")
    public ResponseEntity<Void> submitChatLogs(
        @RequestBody @Valid ChatLogBatchRequest request,
        HttpServletRequest httpRequest) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        minecraftChatLogService.submitChatLogs(server, request.entries()
            .stream()
            .map(this::toChatLogCommand)
            .toList());
        return ResponseEntity.ok().build();
    }

    private MinecraftChatLogService.ChatLogCommand toChatLogCommand(ChatLogEntryRequest request) {
        return new MinecraftChatLogService.ChatLogCommand(
            request.uuid(),
            request.username(),
            request.message(),
            request.timestamp(),
            request.server()
        );
    }

    @PostMapping("/command-log")
    public ResponseEntity<Void> submitCommandLogs(
        @RequestBody @Valid CommandLogBatchRequest request,
        HttpServletRequest httpRequest) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        minecraftChatLogService.submitCommandLogs(server, request.entries()
            .stream()
            .map(this::toCommandLogCommand)
            .toList());
        return ResponseEntity.ok().build();
    }

    private MinecraftChatLogService.CommandLogCommand toCommandLogCommand(CommandLogEntryRequest request) {
        return new MinecraftChatLogService.CommandLogCommand(
            request.uuid(),
            request.username(),
            request.command(),
            request.timestamp(),
            request.server()
        );
    }

    @GetMapping("/{uuid}/chat-logs")
    public ResponseEntity<Map<String, Object>> getChatLogs(
        @PathVariable String uuid,
        @RequestParam(defaultValue = "200") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES) int limit,
        HttpServletRequest httpRequest) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return ResponseEntity.ok(Map.of(
            "entries", minecraftChatLogService.getChatLogs(server, uuid, limit)
        ));
    }

    @GetMapping("/{uuid}/command-logs")
    public ResponseEntity<Map<String, Object>> getCommandLogs(
        @PathVariable String uuid,
        @RequestParam(defaultValue = "200") @Min(RequestValidationLimits.PAGINATION_LIMIT_MIN) @Max(RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES) int limit,
        HttpServletRequest httpRequest) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return ResponseEntity.ok(Map.of(
            "entries", minecraftChatLogService.getCommandLogs(server, uuid, limit)
        ));
    }
}