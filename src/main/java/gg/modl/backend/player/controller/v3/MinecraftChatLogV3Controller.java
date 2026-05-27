package gg.modl.backend.player.controller.v3;

import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.player.service.MinecraftChatLogService;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.ChatLogBatchRequest;
import gg.modl.proto.modl.v1.ChatLogEntry;
import gg.modl.proto.modl.v1.CommandLogBatchRequest;
import gg.modl.proto.modl.v1.CommandLogEntry;
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
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/players")
@RequiredArgsConstructor
public class MinecraftChatLogV3Controller {
    private static final SimpleResponse SUCCESS_RESPONSE = SimpleResponse.newBuilder()
        .setSuccess(true)
        .build();

    private final MinecraftChatLogService minecraftChatLogService;

    @PostMapping(
        value = "/chat-log",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SimpleResponse> submitChatLogs(
        @RequestBody @Valid ChatLogBatchRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        minecraftChatLogService.submitChatLogs(server, request.getEntriesList()
            .stream()
            .map(this::toChatLogCommand)
            .toList());
        return ResponseEntity.ok(SUCCESS_RESPONSE);
    }

    private MinecraftChatLogService.ChatLogCommand toChatLogCommand(ChatLogEntry request) {
        return new MinecraftChatLogService.ChatLogCommand(
            request.getUuid(),
            request.getUsername(),
            request.getMessage(),
            request.getTimestamp(),
            request.getServer()
        );
    }

    @PostMapping(
        value = "/command-log",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SimpleResponse> submitCommandLogs(
        @RequestBody @Valid CommandLogBatchRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        minecraftChatLogService.submitCommandLogs(server, request.getEntriesList()
            .stream()
            .map(this::toCommandLogCommand)
            .toList());
        return ResponseEntity.ok(SUCCESS_RESPONSE);
    }

    private MinecraftChatLogService.CommandLogCommand toCommandLogCommand(CommandLogEntry request) {
        return new MinecraftChatLogService.CommandLogCommand(
            request.getUuid(),
            request.getUsername(),
            request.getCommand(),
            request.getTimestamp(),
            request.getServer()
        );
    }
}
