package gg.modl.backend.replay.controller.v3;

import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.replay.dto.InitReplayUploadResponse;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.InitReplayUploadEnvelopeResponse;
import gg.modl.proto.modl.v1.InitReplayUploadRequest;
import gg.modl.proto.modl.v1.ReplayConfirmResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/replays")
@RequiredArgsConstructor
public class MinecraftReplayV3Controller {
    private final ReplayService replayService;

    @PostMapping(
        value = "/upload",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<InitReplayUploadEnvelopeResponse> initUpload(
        @RequestBody @Valid InitReplayUploadRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        InitReplayUploadResponse response = replayService.initUpload(
            server,
            request.getMcVersion(),
            request.getFileSize()
        );

        return ResponseEntity.ok(InitReplayUploadEnvelopeResponse.newBuilder()
            .setStatus(200)
            .setReplayId(response.replayId())
            .setUploadUrl(response.uploadUrl())
            .setMethod(response.method())
            .putAllRequiredHeaders(response.requiredHeaders())
            .build());
    }

    @PostMapping(
        value = "/confirm/{replayId}",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<ReplayConfirmResponse> confirmUpload(
        @PathVariable String replayId,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        boolean success = replayService.confirmUpload(server, replayId);

        if (!success) {
            return ResponseEntity.badRequest()
                .body(ReplayConfirmResponse.newBuilder()
                    .setStatus(400)
                    .setSuccess(false)
                    .setMessage("Upload verification failed")
                    .build());
        }

        return ResponseEntity.ok(ReplayConfirmResponse.newBuilder()
            .setStatus(200)
            .setSuccess(true)
            .setMessage("Upload confirmed")
            .build());
    }
}
