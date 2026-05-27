package gg.modl.backend.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.AuthService;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffService;
import jakarta.servlet.http.Cookie;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class PanelAuthControllerTest {

    @Test
    void logoutInvalidatesOnlyCurrentCookieSessions() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        SessionService sessionService = mock(SessionService.class);
        PanelAuthController controller = createController(authConfiguration, sessionService);
        Server server = server();
        AuthSessionData session = new AuthSessionData("session-1", "staff@example.com", new Date(), new Date(), null, null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/panel/auth/logout");
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION, session);
        request.setCookies(
            new Cookie("MODL_SESSION", "session-1"),
            new Cookie("MODL_SESSION", "session-2")
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(request, response);

        verify(sessionService).invalidateSession(server, "session-1");
        verify(sessionService).invalidateSession(server, "session-2");
        verify(sessionService, never()).invalidateAllSessionsForEmail(any(Server.class), eq("staff@example.com"));
    }

    @Test
    void emailChangeStillInvalidatesOldEmailSessions() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        AuthService authService = mock(AuthService.class);
        SessionService sessionService = mock(SessionService.class);
        StaffService staffService = mock(StaffService.class);
        PermissionService permissionService = mock(PermissionService.class);
        PanelAuthController controller = new PanelAuthController(
            authService,
            sessionService,
            authConfiguration,
            staffService,
            permissionService,
            new CookieUtil(authConfiguration)
        );
        Server server = server();
        Staff staff = new Staff();
        staff.setEmail("new@example.com");
        AuthSessionData currentSession = new AuthSessionData("old-session", "old@example.com", new Date(), new Date(), null, null);
        AuthSessionData newSession = new AuthSessionData("new-session", "new@example.com", new Date(), new Date(), null, null);
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/v1/panel/auth/email");
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION, currentSession);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(permissionService.isSuperAdmin(server, "old@example.com")).thenReturn(false);
        when(permissionService.isAuthorizedEmail(server, "new@example.com")).thenReturn(true);
        when(authService.verifyCode(server, "new@example.com", "123456")).thenReturn(true);
        when(staffService.updateEmail(server, "old@example.com", "new@example.com", false)).thenReturn(Optional.of(staff));
        when(sessionService.createSession(server, "new@example.com", "127.0.0.1", null)).thenReturn(newSession);

        controller.updateEmail(request, response, new PanelAuthController.UpdateEmailWithCodeRequest("new@example.com", "123456"));

        verify(sessionService).invalidateAllSessionsForEmail(server, "old@example.com");
        verify(sessionService).createSession(server, "new@example.com", "127.0.0.1", null);
    }

    private PanelAuthController createController(AuthConfiguration authConfiguration, SessionService sessionService) {
        return new PanelAuthController(
            mock(AuthService.class),
            sessionService,
            authConfiguration,
            mock(StaffService.class),
            mock(PermissionService.class),
            new CookieUtil(authConfiguration)
        );
    }

    private Server server() {
        Server server = new Server("Alpha", "alpha", "server_alpha", "owner@example.com", true, ServerPlan.FREE);
        server.setId("server_alpha");
        return server;
    }
}
