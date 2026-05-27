package gg.modl.backend.admin.service;

import gg.modl.backend.admin.data.AdminUser;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.database.mongo.repository.AdminUserMongoRepository;
import gg.modl.backend.infrastructure.rest.RESTSecurityRole;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuthService {
    private static final long ADMIN_SESSION_MAX_AGE_SECONDS = 24 * 60 * 60;
    private final AdminUserMongoRepository adminUserRepository;
    private final SessionService sessionService;

    public @NotNull Optional<AdminUser> findByEmail(@NotNull String email) {
        return adminUserRepository.findByEmailIgnoreCase(email);
    }

    public void updateLastActivity(@NotNull String email, @Nullable String clientIp) {
        adminUserRepository.updateLastActivity(email, clientIp, new Date());
    }

    public @NotNull Optional<AdminSession> getAuthenticatedSession(@NotNull HttpServletRequest request) {
        String sessionId = extractSessionId(request);
        if (sessionId == null) {
            return Optional.empty();
        }

        Optional<AuthSessionData> sessionOpt = sessionService.findAndRefreshAdminSession(sessionId);
        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }

        AuthSessionData session = sessionOpt.get();
        if (isAdminSessionExpired(session)) {
            sessionService.invalidateAdminSession(sessionId);
            return Optional.empty();
        }

        return findByEmail(session.getEmail())
            .map(admin -> new AdminSession(admin.getId(), session.getEmail(), session.getCreatedAt()));
    }

    public @Nullable String extractSessionId(@NotNull HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (RESTSecurityRole.ADMIN_SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public @NotNull Set<String> extractSessionIds(@NotNull HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Set.of();
        }

        return Arrays.stream(cookies)
            .filter(cookie -> RESTSecurityRole.ADMIN_SESSION_COOKIE.equals(cookie.getName()))
            .map(Cookie::getValue)
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isAdminSessionExpired(AuthSessionData session) {
        return session.getCreatedAt().toInstant().plusSeconds(ADMIN_SESSION_MAX_AGE_SECONDS).isBefore(Instant.now());
    }

    public record AdminSession(String adminId, String email, Date createdAt) {}
}
