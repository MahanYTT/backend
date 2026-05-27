package gg.modl.backend.admin.controller;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.admin.service.AdminAuthService;
import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.AuthService;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.infrastructure.rest.RESTSecurityRole;
import gg.modl.backend.infrastructure.util.CookieUtil;
import jakarta.servlet.http.Cookie;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AdminAuthControllerTest {

    @Test
    void logoutInvalidatesOnlyCurrentCookieSessions() {
        AdminAuthService adminAuthService = mock(AdminAuthService.class);
        SessionService sessionService = mock(SessionService.class);
        AdminAuthController controller = new AdminAuthController(
            adminAuthService,
            mock(AuthService.class),
            sessionService,
            new CookieUtil(new AuthConfiguration())
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/admin/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthSessionData firstSession = new AuthSessionData("admin-session-1", "admin@example.com", new Date(), new Date(), null, null);
        AuthSessionData secondSession = new AuthSessionData("admin-session-2", "admin@example.com", new Date(), new Date(), null, null);

        request.setCookies(
            new Cookie(RESTSecurityRole.ADMIN_SESSION_COOKIE, "admin-session-1"),
            new Cookie(RESTSecurityRole.ADMIN_SESSION_COOKIE, "admin-session-2")
        );
        when(adminAuthService.extractSessionIds(request)).thenReturn(new LinkedHashSet<>(List.of("admin-session-1", "admin-session-2")));
        lenient().when(sessionService.findValidAdminSession("admin-session-1")).thenReturn(Optional.of(firstSession));
        lenient().when(sessionService.findValidAdminSession("admin-session-2")).thenReturn(Optional.of(secondSession));

        controller.logout(request, response);

        verify(sessionService).invalidateAdminSession("admin-session-1");
        verify(sessionService).invalidateAdminSession("admin-session-2");
        verify(sessionService, never()).invalidateAllAdminSessionsForEmail("admin@example.com");
    }
}
