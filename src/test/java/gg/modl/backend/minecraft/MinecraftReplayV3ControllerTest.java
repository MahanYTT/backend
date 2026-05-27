package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.infrastructure.exception.ErrorResponseDTO;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.replay.controller.v1.MinecraftReplayController;
import gg.modl.backend.replay.controller.v3.MinecraftReplayV3Controller;
import gg.modl.backend.replay.dto.InitReplayUploadResponse;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.InitReplayUploadEnvelopeResponse;
import gg.modl.proto.modl.v1.InitReplayUploadRequest;
import gg.modl.proto.modl.v1.ReplayConfirmResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftReplayV3ControllerTest {
    @Mock private ReplayService replayService;
    private MockMvc v3MockMvc;
    private MockMvc v1MockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        v3MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftReplayV3Controller(replayService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(post("/").requestAttr(RequestAttribute.SERVER, server))
            .build();

        v1MockMvc = MockMvcBuilders.standaloneSetup(new MinecraftReplayController(replayService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .defaultRequest(post("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3UploadAcceptsBinaryRequestAndReturnsBinaryEnvelope() throws Exception {
        when(replayService.initUpload(same(server), eq("1.21.4"), eq(2048L)))
            .thenReturn(new InitReplayUploadResponse(
                "replay-1",
                "https://storage.example/upload",
                "PUT",
                Map.of("Content-Type", "application/octet-stream", "x-amz-meta-server", "demo")
            ));

        InitReplayUploadRequest request = InitReplayUploadRequest.newBuilder()
            .setMcVersion("1.21.4")
            .setFileSize(2048L)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/replays/upload")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        InitReplayUploadEnvelopeResponse response = InitReplayUploadEnvelopeResponse.parseFrom(
            result.getResponse().getContentAsByteArray()
        );
        assertEquals(200, response.getStatus());
        assertEquals("replay-1", response.getReplayId());
        assertEquals("https://storage.example/upload", response.getUploadUrl());
        assertEquals("PUT", response.getMethod());
        assertEquals(Map.of("Content-Type", "application/octet-stream", "x-amz-meta-server", "demo"),
            response.getRequiredHeadersMap());
        verify(replayService).initUpload(same(server), eq("1.21.4"),
            eq(2048L));
    }

    @Test
    void v3UploadRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/replays/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(415, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getCode());
    }

    @Test
    void v3UploadValidationFailureReturnsBinaryApiErrorWithFieldViolations() throws Exception {
        InitReplayUploadRequest request = InitReplayUploadRequest.newBuilder()
            .setMcVersion("   ")
            .setFileSize(0L)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/replays/upload")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("mc_version")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("file_size")));
    }

    @Test
    void v3UploadServiceValidationExceptionReturnsBinaryApiError() throws Exception {
        when(replayService.initUpload(same(server), eq("1.21.4"), eq(2048L)))
            .thenThrow(new ValidationException("Storage quota exceeded"));

        InitReplayUploadRequest request = InitReplayUploadRequest.newBuilder()
            .setMcVersion("1.21.4")
            .setFileSize(2048L)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/replays/upload")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertEquals("Storage quota exceeded", error.getMessage());
    }

    @Test
    void v3ConfirmSuccessReturnsBinaryResponse() throws Exception {
        when(replayService.confirmUpload(same(server), eq("replay-1")))
            .thenReturn(true);

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/replays/confirm/replay-1")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ReplayConfirmResponse response = ReplayConfirmResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertTrue(response.getSuccess());
        assertEquals("Upload confirmed", response.getMessage());
        verify(replayService).confirmUpload(same(server), eq("replay-1"));
    }

    @Test
    void v3ConfirmFailureReturnsBadRequestBinaryResponse() throws Exception {
        when(replayService.confirmUpload(same(server), eq("replay-1")))
            .thenReturn(false);

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/replays/confirm/replay-1")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ReplayConfirmResponse response = ReplayConfirmResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, response.getStatus());
        assertFalse(response.getSuccess());
        assertEquals("Upload verification failed", response.getMessage());
    }

    @Test
    void v3UploadRejectsJsonAcceptWithBinaryApiError() throws Exception {
        InitReplayUploadRequest request = InitReplayUploadRequest.newBuilder()
            .setMcVersion("1.21.4")
            .setFileSize(2048L)
            .build();

        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/replays/upload")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(MediaType.APPLICATION_JSON)
                .content(request.toByteArray()))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
    }

    @Test
    void v3ConfirmRejectsJsonAcceptWithBinaryApiError() throws Exception {
        MvcResult result = v3MockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/replays/confirm/replay-1")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(406, error.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", error.getCode());
    }

    @Test
    void v1UploadInvalidRequestStillReturnsJsonError() throws Exception {
        MvcResult result = v1MockMvc.perform(post(RESTMappingV1.MINECRAFT_REPLAYS + "/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mcVersion": "",
                      "fileSize": 0
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = new ObjectMapper()
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertEquals("Invalid data provided.", error.error());
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
    }
}
