package gg.modl.backend.alert.controller;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.alert.data.SystemAlert;
import gg.modl.backend.alert.data.SystemAlertAudience;
import gg.modl.backend.alert.data.SystemAlertSeverity;
import gg.modl.backend.alert.dto.request.CreateSystemAlertRequest;
import gg.modl.backend.alert.dto.response.AdminSystemAlertResponse;
import gg.modl.backend.alert.service.SystemAlertService;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AdminSystemAlertControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAlertReturnsAdminResponseDtoNotMongoDocument() {
        SystemAlertService alertService = mock(SystemAlertService.class);
        AdminSystemAlertController controller = new AdminSystemAlertController(alertService);
        Date expiresAt = new Date(System.currentTimeMillis() + 60_000);
        CreateSystemAlertRequest request = new CreateSystemAlertRequest(
            "Maintenance soon",
            SystemAlertSeverity.WARNING,
            SystemAlertAudience.ALL_PANEL_USERS,
            expiresAt
        );
        when(alertService.createAlert(request, "admin@example.com")).thenReturn(alert("alert-1", expiresAt));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin@example.com", null));

        ResponseEntity<?> response = controller.createAlert(request);

        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertInstanceOf(AdminSystemAlertResponse.class, body.get("data"));
    }

    private static SystemAlert alert(String id, Date expiresAt) {
        return SystemAlert.builder()
            .id(id)
            .message("Maintenance soon")
            .severity(SystemAlertSeverity.WARNING)
            .audience(SystemAlertAudience.ALL_PANEL_USERS)
            .expiresAt(expiresAt)
            .createdAt(new Date(0))
            .updatedAt(new Date(0))
            .createdBy("admin@example.com")
            .updatedBy("admin@example.com")
            .build();
    }
}
