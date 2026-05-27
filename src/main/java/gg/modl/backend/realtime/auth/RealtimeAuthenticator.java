package gg.modl.backend.realtime.auth;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.util.HostExtractionUtil;
import gg.modl.backend.server.ServerService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.ApiKeySettingsService;
import gg.modl.proto.modl.v1.ClientHello;
import gg.modl.proto.modl.v1.ClientKind;
import gg.modl.proto.modl.v1.ErrorCode;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RealtimeAuthenticator {
    private final ApiKeySettingsService apiKeySettingsService;
    private final SessionService sessionService;
    private final ServerService serverService;
    private final AuthConfiguration authConfiguration;
    private final RealtimeOriginValidator originValidator;

    public RealtimePrincipal authenticate(HttpHeaders headers, ClientHello hello) throws RealtimeAuthenticationException {
        if (hello.getClientKind() == ClientKind.CLIENT_KIND_PANEL) {
            return authenticatePanel(headers, hello);
        }
        if (hello.getClientKind() == ClientKind.CLIENT_KIND_MINECRAFT_PLUGIN) {
            return authenticateMinecraft(headers, hello);
        }
        throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_UNAUTHORIZED, "Unsupported realtime client kind");
    }

    private RealtimePrincipal authenticatePanel(HttpHeaders headers, ClientHello hello) throws RealtimeAuthenticationException {
        String serverDomain = resolvePanelServerDomain(hello);
        if (serverDomain == null) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_FORBIDDEN, "Missing or invalid tenant scope");
        }

        if (!originValidator.isAllowedPanelOrigin(headers.getOrigin(), serverDomain)) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_FORBIDDEN, "Origin is not allowed");
        }

        Server server = serverService.getServerFromDomain(serverDomain);
        if (server == null) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_FORBIDDEN, "Invalid tenant host");
        }

        validateHelloScope(server, hello);

        String sessionToken = findCookie(headers, authConfiguration.getSessionCookieName())
            .orElseThrow(() -> new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_UNAUTHORIZED, "Missing panel session"));

        AuthSessionData session = sessionService.findAndRefreshSession(server, sessionToken)
            .orElseThrow(() -> new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_UNAUTHORIZED, "Invalid panel session"));

        return RealtimePrincipal.panel(server, session.getEmail());
    }

    @Nullable
    private String resolvePanelServerDomain(ClientHello hello) {
        String requestedDomain = optionalString(hello.hasServerName(), hello.getServerName());
        if (requestedDomain != null) {
            return HostExtractionUtil.normalizeServerDomain(requestedDomain);
        }
        return null;
    }

    private RealtimePrincipal authenticateMinecraft(HttpHeaders headers, ClientHello hello) throws RealtimeAuthenticationException {
        String apiKey = headers.getFirst(RequestHeader.API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_UNAUTHORIZED, "Missing API key");
        }

        Server server = apiKeySettingsService.findServerByApiKey(apiKey);
        if (server == null) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_UNAUTHORIZED, "Invalid API key");
        }

        validateHelloScope(server, hello);
        return RealtimePrincipal.minecraft(server, optionalString(hello.hasServerInstanceId(), hello.getServerInstanceId()));
    }

    private void validateHelloScope(Server server, ClientHello hello) throws RealtimeAuthenticationException {
        if (hello.hasTenantId() && !hello.getTenantId().isBlank() && !hello.getTenantId().equals(server.getId())) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_FORBIDDEN, "Invalid tenant scope");
        }
        if (hello.hasServerId() && !hello.getServerId().isBlank() && !hello.getServerId().equals(server.getId())) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_FORBIDDEN, "Invalid server scope");
        }
    }

    private Optional<String> findCookie(HttpHeaders headers, String name) {
        List<String> cookieHeaders = headers.get(HttpHeaders.COOKIE);
        if (cookieHeaders == null) {
            return Optional.empty();
        }

        return cookieHeaders.stream()
            .flatMap(header -> List.of(header.split(";")).stream())
            .map(String::trim)
            .map(cookie -> cookie.split("=", 2))
            .filter(parts -> parts.length == 2 && name.equals(parts[0]))
            .map(parts -> parts[1])
            .filter(value -> value != null && !value.isBlank())
            .findFirst();
    }

    @Nullable
    private String optionalString(boolean present, String value) {
        if (!present || value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
