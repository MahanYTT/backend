package gg.modl.backend.replay.controller;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.replay.dto.PublicReplayResponse;
import gg.modl.backend.replay.dto.SubmitReplayLabelsRequest;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_REPLAYS)
@RequiredArgsConstructor
public class PublicReplayController {
    private final ReplayService replayService;

    @GetMapping("/{replayId}")
    public ResponseEntity<?> getReplay(
        @PathVariable String replayId,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);

        return replayService.getPublicReplay(server, replayId)
            .<ResponseEntity<?>>map(replay -> ResponseEntity.ok(Map.of(
                "replayId", replay.replayId(),
                "mcVersion", replay.mcVersion(),
                "fileSize", replay.fileSize(),
                "timestamp", replay.timestamp(),
                "replayUrl", replay.replayUrl(),
                "status", replay.status(),
                "labeled", replay.labeled()
            )))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", "Replay not found"
            )));
    }

    @PostMapping("/{replayId}/label")
    public ResponseEntity<?> submitLabels(
        @PathVariable String replayId,
        @RequestBody @Valid SubmitReplayLabelsRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);

        ReplayService.SubmitLabelsResult result = replayService.submitLabels(server, replayId, request.players());
        if (result == ReplayService.SubmitLabelsResult.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "message", "Replay not found"
            ));
        }
        if (result == ReplayService.SubmitLabelsResult.ALREADY_LABELED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", 409,
                "message", "This replay has already been labeled"
            ));
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
