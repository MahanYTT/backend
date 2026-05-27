package gg.modl.backend.admin.controller;

import gg.modl.backend.admin.data.AdminUser;
import gg.modl.backend.admin.service.AdminAuthService;
import gg.modl.backend.auth.AuthService;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTSecurityRole;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.ADMIN_AUTH)
@RequiredArgsConstructor
public class AdminAuthController {
    private final AdminAuthService adminAuthService;
    private final AuthService authService;
    private final SessionService sessionService;
    private final CookieUtil cookieUtil;
    private static final long SESSION_MAX_AGE = 24 * 60 * 60; // 24 hours

    @PostMapping("/request-code")
    public ResponseEntity<?> requestCode(@RequestBody @Valid RequestCodeRequest request) throws MessagingException, UnsupportedEncodingException {

        Optional<AdminUser> adminOpt = adminAuthService.findByEmail(request.email());
        if (adminOpt.isPresent()) {
            authService.sendAdminLoginCode(request.email());
        }

        return ResponseEntity.ok(new ApiResponse(true, "If this email is registered, a verification code has been sent"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
        HttpServletRequest request,
        HttpServletResponse response,
        @RequestBody @Valid LoginRequest loginRequest) {

        // Always verify code regardless of user existence to prevent timing-based enumeration
        boolean codeValid = authService.verifyAdminCode(loginRequest.email(), loginRequest.code());
        Optional<AdminUser> adminOpt = adminAuthService.findByEmail(loginRequest.email());

        if (adminOpt.isEmpty() || !codeValid) {
            return ResponseEntity.status(401).body(new ApiResponse(false, "Invalid credentials"));
        }

        AdminUser admin = adminOpt.get();
        String clientIp = RequestUtil.getClientIp(request);
        adminAuthService.updateLastActivity(admin.getEmail(), clientIp);

        AuthSessionData session = sessionService.createAdminSession(admin.getEmail());

        response.addCookie(cookieUtil.createSessionCookie(RESTSecurityRole.ADMIN_SESSION_COOKIE, session.getId(), SESSION_MAX_AGE));

        return ResponseEntity.ok(new LoginResponse(true, "Login successful",
            new UserData(admin.getEmail(), admin.getLastActivityAt())));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        for (String sessionId : adminAuthService.extractSessionIds(request)) {
            sessionService.invalidateAdminSession(sessionId);
        }

        for (Cookie expiredCookie : cookieUtil.createExpiredSessionCookies(RESTSecurityRole.ADMIN_SESSION_COOKIE)) {
            response.addCookie(expiredCookie);
        }

        return ResponseEntity.ok(new ApiResponse(true, "Logout successful"));
    }

    @GetMapping("/session")
    public ResponseEntity<?> getSession(HttpServletRequest request) {
        String sessionId = adminAuthService.extractSessionId(request);
        if (sessionId == null) {
            return ResponseEntity.status(401).body(new ApiResponse(false, "Not authenticated"));
        }

        Optional<AuthSessionData> sessionOpt = sessionService.findAndRefreshAdminSession(sessionId);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.status(401).body(new ApiResponse(false, "Session expired"));
        }
        AuthSessionData session = sessionOpt.get();
        if (adminAuthService.isAdminSessionExpired(session)) {
            sessionService.invalidateAdminSession(sessionId);
            return ResponseEntity.status(401).body(new ApiResponse(false, "Session expired"));
        }

        Optional<AdminUser> adminOpt = adminAuthService.findByEmail(session.getEmail());
        if (adminOpt.isEmpty()) {
            sessionService.invalidateAdminSession(sessionId);
            return ResponseEntity.status(401).body(new ApiResponse(false, "User not found"));
        }

        AdminUser admin = adminOpt.get();
        return ResponseEntity.ok(new SessionResponse(true,
            new SessionData(admin.getEmail(), admin.getLastActivityAt(), admin.getLoggedInIps(), true)));
    }

    public record RequestCodeRequest(@Email @NotBlank @Size(max = RequestValidationLimits.EMAIL_MAX_LENGTH) String email) {}

    public record LoginRequest(@Email @NotBlank @Size(max = RequestValidationLimits.EMAIL_MAX_LENGTH) String email, @NotBlank @Size(max = RequestValidationLimits.TICKET_VERIFY_CODE_MAX_LENGTH) String code) {}

    public record ApiResponse(boolean success, String message) {}

    public record LoginResponse(boolean success, String message, UserData data) {}

    public record UserData(String email, Date lastActivityAt) {}

    public record SessionResponse(boolean success, SessionData data) {}

    public record SessionData(String email, Date lastActivityAt, List<String> loggedInIps, boolean isAuthenticated) {}

}
