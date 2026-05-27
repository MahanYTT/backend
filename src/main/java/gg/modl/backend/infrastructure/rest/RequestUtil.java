package gg.modl.backend.infrastructure.rest;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Slf4j
public final class RequestUtil {
    private static volatile boolean warnedAboutProxy = false;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final boolean TRUST_PROXY_HEADERS = Boolean.parseBoolean(
        Optional.ofNullable(System.getProperty("modl.trust-proxy-headers"))
            .orElseGet(() -> Optional.ofNullable(System.getenv("MODL_TRUST_PROXY_HEADERS")).orElse("false"))
    );

    @NotNull
    public static Server getRequestServer(HttpServletRequest request) {
        return Objects.requireNonNull((Server) request.getAttribute(RequestAttribute.SERVER), "Server should not be null if being called from panel route!");
    }

    @Nullable
    public static String getSessionEmail(HttpServletRequest request) {
        AuthSessionData session = getSession(request);
        return session != null ? session.getEmail() : null;
    }

    @Nullable
    public static AuthSessionData getSession(HttpServletRequest request) {
        return (AuthSessionData) request.getAttribute(RequestAttribute.SESSION);
    }

    @NotNull
    public static String getCurrentUsername(HttpServletRequest request) {
        AuthSessionData session = getSession(request);
        if (session == null || session.getEmail() == null) {
            return "Unknown";
        }
        return session.getEmail();
    }

    public static String getClientIp(HttpServletRequest request) {
        if (TRUST_PROXY_HEADERS) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                String firstHop = xForwardedFor.split(",")[0].trim();
                if (!firstHop.isEmpty()) {
                    return firstHop;
                }
            }
            String xRealIp = request.getHeader("X-Real-IP");
            if (xRealIp != null && !xRealIp.isEmpty()) {
                return xRealIp;
            }
        } else if (!warnedAboutProxy) {
            String remoteAddr = request.getRemoteAddr();
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                log.warn("Request has X-Forwarded-For header ({}) but MODL_TRUST_PROXY_HEADERS is not set. "
                    + "Client IP will be reported as {}. Set MODL_TRUST_PROXY_HEADERS=true if running behind a proxy.",
                    xForwardedFor, remoteAddr);
                warnedAboutProxy = true;
            }
        }
        return request.getRemoteAddr();
    }

    public static boolean trustsProxyHeaders() {
        return TRUST_PROXY_HEADERS;
    }

    public static String generateSecureToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private RequestUtil() {
        throw new UnsupportedOperationException();
    }
}
