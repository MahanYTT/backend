package gg.modl.backend.replay.controller.v1;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.replay.dto.InitReplayUploadRequest;
import gg.modl.backend.replay.dto.InitReplayUploadResponse;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_REPLAYS)
@RequiredArgsConstructor
public class MinecraftReplayController {
    private final ReplayService replayService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> initUpload(
        @RequestBody @Valid InitReplayUploadRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);

        InitReplayUploadResponse response = replayService.initUpload(
            server, request.mcVersion(), request.fileSize(), request.targetUuid(), request.targetName()
        );

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "replayId", response.replayId(),
            "uploadUrl", response.uploadUrl(),
            "method", response.method(),
            "requiredHeaders", response.requiredHeaders()
        ));
    }

    @PostMapping("/confirm/{replayId}")
    public ResponseEntity<Map<String, Object>> confirmUpload(
        @PathVariable String replayId,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        boolean success = replayService.confirmUpload(server, replayId);

        if (!success) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "success", false,
                "message", "Upload verification failed"
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "success", true,
            "message", "Upload confirmed"
        ));
    }
}
