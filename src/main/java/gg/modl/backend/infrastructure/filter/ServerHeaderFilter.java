package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.ServerService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.infrastructure.util.HostExtractionUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

public class ServerHeaderFilter extends OncePerRequestFilter {
    private final ServerService serverService;
    private final boolean developmentMode;
    private final String devServerDomain;
    private final Set<String> systemOrigins;

    private static final Set<String> EXCLUDED_PATHS = Set.of(
        RESTMappingV1.PUBLIC_REGISTRATION,
        RESTMappingV1.PUBLIC_EVIDENCE_UPLOAD
    );

    public ServerHeaderFilter(ServerService serverService) {
        this(serverService, false, null, null);
    }

    public ServerHeaderFilter(
        ServerService serverService,
        boolean developmentMode,
        @Nullable String devServerDomain,
        @Nullable String systemOrigins
    ) {
        this.serverService = serverService;
        this.developmentMode = developmentMode;
        this.devServerDomain = devServerDomain;
        this.systemOrigins = HostExtractionUtil.parseCommaSeparated(systemOrigins);
    }

    @Override
    protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain chain)
        throws ServletException, IOException {
        String requestHost = resolveRequestHost(request);
        String legacyServerDomainHeader = HostExtractionUtil.normalizeServerDomain(request.getHeader(RequestHeader.SERVER_DOMAIN));

        String serverDomain = requestHost != null ? requestHost : legacyServerDomainHeader;

        // In development mode, use the configured dev server domain for localhost requests.
        if (developmentMode && devServerDomain != null && !devServerDomain.isBlank()) {
            if (serverDomain == null || serverDomain.isBlank() || isLocalhost(serverDomain)) {
                serverDomain = devServerDomain;
            }
        }

        if (serverDomain == null || serverDomain.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing server domain host");
            return;
        }

        String normalizedServerDomain = HostExtractionUtil.normalizeServerDomain(serverDomain);
        if (normalizedServerDomain == null || normalizedServerDomain.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid server domain format");
            return;
        }

        if (requestHost != null && legacyServerDomainHeader != null
            && !requestHost.equalsIgnoreCase(legacyServerDomainHeader)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Mismatched server domain headers");
            return;
        }

        Server server = serverService.getServerFromDomain(normalizedServerDomain);
        if (server == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Invalid server domain");
            return;
        }

        if (!isOriginAllowedForServer(request, normalizedServerDomain)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin is not allowed for requested server domain");
            return;
        }

        request.setAttribute(RequestAttribute.SERVER_DOMAIN, normalizedServerDomain);
        request.setAttribute(RequestAttribute.SERVER, server);
        chain.doFilter(request, response);
    }

    @Nullable
    private String resolveRequestHost(HttpServletRequest request) {
        if (RequestUtil.trustsProxyHeaders()) {
            String forwardedHost = extractFirstForwardedHost(request.getHeader(RequestHeader.FORWARDED_HOST));
            if (forwardedHost != null) {
                return forwardedHost;
            }
        }

        String host = HostExtractionUtil.normalizeServerDomain(request.getHeader("Host"));
        if (host != null) {
            return host;
        }

        return HostExtractionUtil.normalizeServerDomain(request.getServerName());
    }

    @Nullable
    private String extractFirstForwardedHost(@Nullable String forwardedHostHeader) {
        if (forwardedHostHeader == null || forwardedHostHeader.isBlank()) {
            return null;
        }

        int separatorIndex = forwardedHostHeader.indexOf(',');
        String firstForwardedHost = separatorIndex >= 0
                                    ? forwardedHostHeader.substring(0, separatorIndex).trim()
                                    : forwardedHostHeader.trim();
        return HostExtractionUtil.normalizeServerDomain(firstForwardedHost);
    }

    private boolean isOriginAllowedForServer(HttpServletRequest request, String serverDomain) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }

        if (systemOrigins.contains(origin)) {
            return true;
        }

        String originHost = HostExtractionUtil.extractHost(origin);
        if (originHost == null) {
            return false;
        }

        if (developmentMode && isLocalhost(originHost)) {
            return true;
        }

        String normalizedDomain = HostExtractionUtil.extractHost(serverDomain);
        return normalizedDomain != null && originHost.equalsIgnoreCase(normalizedDomain);
    }

    private boolean isLocalhost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || "0.0.0.0".equals(normalized)
               || "::1".equals(normalized) || normalized.startsWith("127.");
    }

}
