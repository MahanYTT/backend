package gg.modl.backend.auth.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.auth.WebAuthnService;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_AUTH + "/webauthn")
@RequiredArgsConstructor
public class WebAuthnController {
    private final WebAuthnService webAuthnService;
    private final SessionService sessionService;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final CookieUtil cookieUtil;

    @PostMapping("/register/options")
    public ResponseEntity<?> registerOptions(HttpServletRequest request) throws JsonProcessingException {
        String email = RequestUtil.getSessionEmail(request);
        if (email == null) {
            throw new UnauthorizedException("Not authenticated");
        }

        Server server = RequestUtil.getRequestServer(request);
        WebAuthnService.StartRegistrationResult result = webAuthnService.startRegistration(server, email);
        Object options = objectMapper.readValue(result.optionsJson(), Object.class);
        return ResponseEntity.ok(Map.of("challengeId", result.challengeId(), "options", options));
    }

    @PostMapping("/register/verify")
    public ResponseEntity<?> registerVerify(
        HttpServletRequest request,
        @RequestBody @Valid RegisterVerifyRequest body) throws IOException {
        String email = RequestUtil.getSessionEmail(request);
        if (email == null) {
            throw new UnauthorizedException("Not authenticated");
        }

        Server server = RequestUtil.getRequestServer(request);
        webAuthnService.finishRegistration(server, email, body.challengeId(), body.response(), body.name());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/credentials")
    public ResponseEntity<?> listCredentials(HttpServletRequest request) {
        String email = RequestUtil.getSessionEmail(request);
        if (email == null) {
            throw new UnauthorizedException("Not authenticated");
        }

        Server server = RequestUtil.getRequestServer(request);
        List<WebAuthnService.CredentialInfo> credentials = webAuthnService.listCredentials(server, email);
        return ResponseEntity.ok(credentials);
    }

    @PatchMapping("/credentials/{id}")
    public ResponseEntity<?> renameCredential(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody @Valid RenameCredentialRequest body) {
        String email = RequestUtil.getSessionEmail(request);
        if (email == null) {
            throw new UnauthorizedException("Not authenticated");
        }

        Server server = RequestUtil.getRequestServer(request);
        boolean updated = webAuthnService.renameCredential(server, email, id, body.name());
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/credentials/{id}")
    public ResponseEntity<?> deleteCredential(
        HttpServletRequest request,
        @PathVariable String id) {
        String email = RequestUtil.getSessionEmail(request);
        if (email == null) {
            throw new UnauthorizedException("Not authenticated");
        }

        Server server = RequestUtil.getRequestServer(request);
        boolean deleted = webAuthnService.deleteCredential(server, email, id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/login/start")
    public ResponseEntity<?> loginStart(HttpServletRequest request) throws JsonProcessingException {
        Server server = RequestUtil.getRequestServer(request);
        WebAuthnService.StartAuthenticationResult result = webAuthnService.startDiscoverableAuthentication(server);
        Object options = objectMapper.readValue(result.optionsJson(), Object.class);
        return ResponseEntity.ok(Map.of("challengeId", result.challengeId(), "options", options));
    }

    @PostMapping("/login/options")
    public ResponseEntity<?> loginOptions(
        HttpServletRequest request,
        @RequestBody @Valid LoginOptionsRequest body) throws JsonProcessingException {
        Server server = RequestUtil.getRequestServer(request);
        // Prevent email enumeration: check if email is authorized first
        if (!permissionService.isAuthorizedEmail(server, body.email())) {
            return ResponseEntity.ok(Map.of("hasPasskeys", false));
        }

        boolean hasPasskeys = webAuthnService.checkHasPasskeys(server, body.email());
        if (!hasPasskeys) {
            return ResponseEntity.ok(Map.of("hasPasskeys", false));
        }

        WebAuthnService.StartAuthenticationResult result = webAuthnService.startAuthentication(server, body.email());
        Object options = objectMapper.readValue(result.optionsJson(), Object.class);
        return ResponseEntity.ok(Map.of(
            "hasPasskeys", true,
            "challengeId", result.challengeId(),
            "options", options
        ));
    }

    @PostMapping("/login/verify")
    public ResponseEntity<?> loginVerify(
        HttpServletRequest request,
        HttpServletResponse response,
        @RequestBody @Valid LoginVerifyRequest body) throws IOException {
        Server server = RequestUtil.getRequestServer(request);
        String email = webAuthnService.finishAuthentication(server, body.challengeId(), body.response());

        if (!permissionService.isAuthorizedEmail(server, email)) {
            throw new ValidationException("Not authorized");
        }

        AuthSessionData session = sessionService.createSession(server, email, RequestUtil.getClientIp(request), request.getHeader("User-Agent"));
        response.addCookie(cookieUtil.createSessionCookie(session.getId()));

        return ResponseEntity.ok(Map.of("success", true));
    }


    public record RegisterVerifyRequest(
        @NotBlank @Size(max = 256) String challengeId,
        @NotBlank @Size(max = 10_000) String response,
        @Size(max = 128) String name
    ) {}

    public record RenameCredentialRequest(@NotBlank @Size(max = 128) String name) {}

    public record LoginOptionsRequest(@Email @NotBlank @Size(max = RequestValidationLimits.EMAIL_MAX_LENGTH) String email) {}

    public record LoginVerifyRequest(
        @NotBlank @Size(max = 256) String challengeId,
        @NotBlank @Size(max = 10_000) String response
    ) {}
}
