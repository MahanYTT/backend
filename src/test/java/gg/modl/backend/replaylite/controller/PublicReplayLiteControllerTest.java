package gg.modl.backend.replaylite.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.replaylite.service.ReplayLiteService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PublicReplayLiteControllerTest {
    private static final UUID REPLAY_ID = UUID.fromString("75f4b741-67df-414c-957b-a8a08222fc30");

    @Test
    void downloadReplayStreamsBytesWithoutRedirectOrPublicCache() {
        ReplayLiteService service = Mockito.mock(ReplayLiteService.class);
        PublicReplayLiteController controller = new PublicReplayLiteController(service);
        byte[] replayBytes = new byte[] {1, 2, 3};
        when(service.getPublicReplayDownload(REPLAY_ID.toString()))
            .thenReturn(Optional.of(new ReplayLiteService.ReplayLiteDownload(
                replayBytes,
                "application/octet-stream",
                Instant.parse("2026-04-25T12:00:00Z")
            )));

        ResponseEntity<?> response = controller.downloadReplay(REPLAY_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(replayBytes, (byte[]) response.getBody());
        assertNull(response.getHeaders().getLocation());
        assertEquals("private, no-store, max-age=0", response.getHeaders().getCacheControl());
        assertEquals("no-cache", response.getHeaders().getPragma());
        assertEquals("inline; filename=\"" + REPLAY_ID + ".modlreplay\"", response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void malformedReplayIdReturnsBadRequestBeforeDownloadLookup() throws Exception {
        ReplayLiteService service = Mockito.mock(ReplayLiteService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PublicReplayLiteController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        mockMvc.perform(get(RESTMappingV1.PUBLIC_REPLAY_LITE_REPLAYS + "/not-a-uuid/download"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
