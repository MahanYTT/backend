package gg.modl.backend.realtime.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.server.ServerService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.service.ApiKeySettingsService;
import gg.modl.proto.modl.v1.ClientHello;
import gg.modl.proto.modl.v1.ClientKind;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
class RealtimeAuthenticatorTest {

    @Test
    void minecraftAuthenticationTreatsServerNameAsAdvisoryMetadata() {
        ApiKeySettingsService apiKeySettingsService = mock(ApiKeySettingsService.class);
        Server server = server();
        when(apiKeySettingsService.findServerByApiKey("api-key")).thenReturn(server);
        RealtimeAuthenticator authenticator = new RealtimeAuthenticator(
            apiKeySettingsService,
            mock(SessionService.class),
            mock(ServerService.class),
            mock(AuthConfiguration.class),
            mock(RealtimeOriginValidator.class)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestHeader.API_KEY, "api-key");
        ClientHello hello = ClientHello.newBuilder()
            .setClientKind(ClientKind.CLIENT_KIND_MINECRAFT_PLUGIN)
            .setServerName("Server 1")
            .build();

        RealtimePrincipal principal = assertDoesNotThrow(() -> authenticator.authenticate(headers, hello));

        assertEquals(RealtimeClientKind.MINECRAFT, principal.clientKind());
        assertEquals(server, principal.server());
    }

    @Test
    void panelAuthenticationUsesExplicitClientHelloTenantDomain() {
        ApiKeySettingsService apiKeySettingsService = mock(ApiKeySettingsService.class);
        SessionService sessionService = mock(SessionService.class);
        ServerService serverService = mock(ServerService.class);
        AuthConfiguration authConfiguration = new AuthConfiguration();
        RealtimeOriginValidator originValidator = mock(RealtimeOriginValidator.class);
        Server server = server();
        when(serverService.getServerFromDomain("tenant.modl.gg")).thenReturn(server);
        when(originValidator.isAllowedPanelOrigin("https://panel.modl.gg", "tenant.modl.gg")).thenReturn(true);
        when(sessionService.findAndRefreshSession(server, "session-token")).thenReturn(Optional.of(session()));
        RealtimeAuthenticator authenticator = new RealtimeAuthenticator(
            apiKeySettingsService,
            sessionService,
            serverService,
            authConfiguration,
            originValidator
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://panel.modl.gg");
        headers.add(HttpHeaders.COOKIE, authConfiguration.getSessionCookieName() + "=session-token");
        headers.set(HttpHeaders.HOST, "panel.modl.gg");
        ClientHello hello = ClientHello.newBuilder()
            .setClientKind(ClientKind.CLIENT_KIND_PANEL)
            .setServerName("tenant.modl.gg")
            .build();

        RealtimePrincipal principal = assertDoesNotThrow(() -> authenticator.authenticate(headers, hello));

        assertEquals(RealtimeClientKind.PANEL, principal.clientKind());
        assertEquals(server, principal.server());
    }

    @Test
    void panelAuthenticationRejectsMissingClientHelloTenantDomain() {
        RealtimeAuthenticator authenticator = new RealtimeAuthenticator(
            mock(ApiKeySettingsService.class),
            mock(SessionService.class),
            mock(ServerService.class),
            new AuthConfiguration(),
            mock(RealtimeOriginValidator.class)
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.HOST, "tenant.modl.gg");
        ClientHello hello = ClientHello.newBuilder()
            .setClientKind(ClientKind.CLIENT_KIND_PANEL)
            .build();

        assertThrows(RealtimeAuthenticationException.class, () -> authenticator.authenticate(headers, hello));
    }

    private Server server() {
        Server server = new Server("Tenant Display Name", "tenant", "tenant_db", "admin@example.com", true, ServerPlan.FREE);
        server.setId("server-id");
        return server;
    }

    private AuthSessionData session() {
        return new AuthSessionData("session-token", "staff@example.com", new Date(), new Date(System.currentTimeMillis() + 1000), null, null);
    }
}
