package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.protobuf.UnknownFieldSet;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.player.controller.v3.MinecraftChatLogV3Controller;
import gg.modl.backend.player.service.MinecraftChatLogService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.ChatLogBatchRequest;
import gg.modl.proto.modl.v1.ChatLogEntry;
import gg.modl.proto.modl.v1.CommandLogBatchRequest;
import gg.modl.proto.modl.v1.CommandLogEntry;
import gg.modl.proto.modl.v1.SimpleResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftChatLogV3ControllerTest {
    @Mock private MinecraftChatLogService minecraftChatLogService;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        mockMvc = MockMvcBuilders.standaloneSetup(new MinecraftChatLogV3Controller(minecraftChatLogService))
            .setControllerAdvice(new GlobalExceptionHandler(), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .defaultRequest(post("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v3ChatLogAcceptsBinaryRequestAndReturnsBinarySimpleResponse() throws Exception {
        ChatLogBatchRequest request = ChatLogBatchRequest.newBuilder()
            .addEntries(ChatLogEntry.newBuilder()
                .setUuid("11111111-2222-3333-4444-555555555555")
                .setUsername("Byteful")
                .setMessage("hello")
                .setTimestamp(1_700_000_000_000L)
                .setServer("hub")
                .build())
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/chat-log")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        SimpleResponse response = SimpleResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertTrue(response.getSuccess());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MinecraftChatLogService.ChatLogCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(minecraftChatLogService).submitChatLogs(same(server), commandsCaptor.capture());
        assertEquals(List.of(new MinecraftChatLogService.ChatLogCommand(
            "11111111-2222-3333-4444-555555555555",
            "Byteful",
            "hello",
            1_700_000_000_000L,
            "hub"
        )), commandsCaptor.getValue());
    }

    @Test
    void v3CommandLogAcceptsBinaryRequestAndReturnsBinarySimpleResponse() throws Exception {
        CommandLogBatchRequest request = CommandLogBatchRequest.newBuilder()
            .addEntries(CommandLogEntry.newBuilder()
                .setUuid("11111111-2222-3333-4444-555555555555")
                .setUsername("Byteful")
                .setCommand("/spawn")
                .setTimestamp(1_700_000_123_000L)
                .setServer("survival")
                .build())
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/command-log")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        SimpleResponse response = SimpleResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertTrue(response.getSuccess());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MinecraftChatLogService.CommandLogCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(minecraftChatLogService).submitCommandLogs(same(server), commandsCaptor.capture());
        assertEquals(List.of(new MinecraftChatLogService.CommandLogCommand(
            "11111111-2222-3333-4444-555555555555",
            "Byteful",
            "/spawn",
            1_700_000_123_000L,
            "survival"
        )), commandsCaptor.getValue());
    }

    @Test
    void v3ChatLogEmptyEntriesReturnsBinaryApiErrorWithFieldViolations() throws Exception {
        ChatLogBatchRequest request = ChatLogBatchRequest.newBuilder()
            .setUnknownFields(UnknownFieldSet.newBuilder()
                .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                .build())
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/chat-log")
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(request.toByteArray()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(400, error.getStatusCode());
        assertEquals("INVALID_ARGUMENT", error.getCode());
        assertFalse(error.getFieldViolationsList().isEmpty());
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("entries")));
    }

    @Test
    void v3ChatLogInvalidEntryReturnsBinaryApiErrorWithFieldViolations() throws Exception {
        ChatLogBatchRequest request = ChatLogBatchRequest.newBuilder()
            .addEntries(ChatLogEntry.newBuilder()
                .setUuid("not-a-uuid")
                .setUsername("!")
                .setMessage("")
                .setTimestamp(-1)
                .setServer("s".repeat(65))
                .build())
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/chat-log")
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
            .anyMatch(violation -> violation.getField().contains("uuid")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("username")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("message")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("timestamp")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("server")));
    }

    @Test
    void v3ChatLogWhitespaceMessageReturnsBinaryApiErrorWithFieldViolation() throws Exception {
        ChatLogBatchRequest request = ChatLogBatchRequest.newBuilder()
            .addEntries(ChatLogEntry.newBuilder()
                .setUuid("11111111-2222-3333-4444-555555555555")
                .setUsername("Byteful")
                .setMessage("   ")
                .setTimestamp(1)
                .setServer("hub")
                .build())
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/chat-log")
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
            .anyMatch(violation -> violation.getField().contains("message")));
    }

    @Test
    void v3CommandLogInvalidEntryReturnsBinaryApiErrorWithFieldViolations() throws Exception {
        CommandLogBatchRequest request = CommandLogBatchRequest.newBuilder()
            .addEntries(CommandLogEntry.newBuilder()
                .setUuid("not-a-uuid")
                .setUsername("!")
                .setCommand("   ")
                .setTimestamp(-1)
                .setServer("s".repeat(65))
                .build())
            .build();

        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/command-log")
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
            .anyMatch(violation -> violation.getField().contains("uuid")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("username")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("command")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("timestamp")));
        assertTrue(error.getFieldViolationsList().stream()
            .anyMatch(violation -> violation.getField().contains("server")));
    }

    @Test
    void v3CommandLogRejectsJsonContentTypeWithBinaryApiError() throws Exception {
        MvcResult result = mockMvc.perform(post(RESTMappingV3.PREFIX_MINECRAFT + "/players/command-log")
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
}
