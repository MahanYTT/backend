package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class PanelPermissionFilter extends OncePerRequestFilter {
    private final PermissionService permissionService;
    private final StaffService staffService;
    private static final List<PermissionMapping> FIXED_PERMISSIONS = List.of(
        new PermissionMapping(RESTMappingV1.PANEL_STAFF, "admin.staff.manage.members"),
        new PermissionMapping(RESTMappingV1.PANEL_ROLES, "admin.staff.manage.roles"),
        new PermissionMapping(RESTMappingV1.PANEL_PLAYERS, "punishment.modify"),
        new PermissionMapping(RESTMappingV1.PANEL_DASHBOARD, "admin.audit.view.dashboard"),
        new PermissionMapping(RESTMappingV1.PREFIX_PANEL + "/stats", "admin.audit.view.dashboard"),
        new PermissionMapping(RESTMappingV1.PANEL_ANALYTICS, "admin.audit.view.analytics"),
        new PermissionMapping(RESTMappingV1.PANEL_AUDIT, "admin.audit.view.logs"),
        new PermissionMapping(RESTMappingV1.PANEL_LOGS, "admin.audit.view.logs"),
        new PermissionMapping(RESTMappingV1.PANEL_TICKETS + "/bulk", "ticket.close.all")
    );
    private static final List<PermissionMapping> RW_PERMISSIONS = List.of(
        new PermissionMapping(RESTMappingV1.PANEL_BILLING, "admin.settings.view.billing", "admin.settings.modify.billing"),
        new PermissionMapping(RESTMappingV1.PANEL_HOMEPAGE_CARDS, "admin.settings.view.content", "admin.settings.modify.content"),
        new PermissionMapping(RESTMappingV1.PANEL_KNOWLEDGEBASE, "admin.settings.view.content", "admin.settings.modify.content"),
        new PermissionMapping(RESTMappingV1.PANEL_MEDIA, "admin.settings.view.content", "admin.settings.modify.content"),
        new PermissionMapping(RESTMappingV1.PANEL_MIGRATION, "admin.settings.view.migration", "admin.settings.modify.migration"),
        new PermissionMapping(RESTMappingV1.PANEL_STORAGE, "admin.settings.view.storage", "admin.settings.modify.storage"),
        new PermissionMapping(RESTMappingV1.PANEL_TICKET_SUBSCRIPTIONS, "ticket.view.all", "ticket.reply.all"),
        new PermissionMapping(RESTMappingV1.PANEL_TICKETS, "ticket.view.all", "ticket.reply.all"),
        new PermissionMapping(RESTMappingV1.PANEL_APPEALS, "ticket.view.all", "appeal.modify"),
        new PermissionMapping(RESTMappingV1.PANEL_SERVER, "admin.settings.view", "admin.settings.modify")
    );
    private static final Set<String> SETTINGS_PUNISHMENT_PATHS = Set.of(
        "/punishment-types", "/status-thresholds", "/ai-moderation",
        "/ai-apply-punishment", "/ai-dismiss-suggestion"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith(RESTMappingV1.PREFIX_PANEL)) {
            return true;
        }
        return startsWithEndpoint(path, RESTMappingV1.PANEL_AUTH);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        @NotNull HttpServletResponse response,
        @NotNull FilterChain filterChain
    ) throws ServletException, IOException {
        Server server = (Server) request.getAttribute(RequestAttribute.SERVER);
        String email = RequestUtil.getSessionEmail(request);
        if (server == null || email == null) {
            deny(response);
            return;
        }

        if (isPanelDashboardAlertsRead(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (permissionService.isSuperAdmin(server, email)) {
            filterChain.doFilter(request, response);
            return;
        }

        String requiredPermission = resolveRequiredPermission(request.getRequestURI(), request.getMethod());
        if (requiredPermission == null) {
            deny(response);
            return;
        }

        Optional<Staff> staffOpt = staffService.getStaffByEmail(server, email);
        String role = staffOpt.map(Staff::getRole).orElse(null);
        boolean authorized = role != null && hasRequiredPermission(server, role, requiredPermission, request.getRequestURI(), request.getMethod());
        if (!authorized) {
            deny(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean hasRequiredPermission(Server server, String role, String requiredPermission, String path, String method) {
        if (permissionService.hasPermission(server, role, requiredPermission)) {
            return true;
        }
        return isAppealReplyWrite(path, method)
               && "appeal.modify".equals(requiredPermission)
               && permissionService.hasPermission(server, role, "ticket.reply.all");
    }

    private static boolean isAppealReplyWrite(String path, String method) {
        return "POST".equalsIgnoreCase(method)
               && startsWithEndpoint(path, RESTMappingV1.PANEL_APPEALS)
               && path.endsWith("/replies");
    }

    private static boolean isPanelDashboardAlertsRead(HttpServletRequest request) {
        return isReadOnly(request.getMethod())
               && startsWithEndpoint(request.getRequestURI(), RESTMappingV1.PANEL_DASHBOARD + "/alerts");
    }

    private void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"Insufficient permissions\"}");
    }

    private String resolveRequiredPermission(String path, String method) {
        for (PermissionMapping mapping : FIXED_PERMISSIONS) {
            if (startsWithEndpoint(path, mapping.endpoint())) {
                return mapping.readPermission();
            }
        }

        if (startsWithEndpoint(path, RESTMappingV1.PANEL_SETTINGS)) {
            return resolveSettingsPermission(path, method);
        }

        for (PermissionMapping mapping : RW_PERMISSIONS) {
            if (startsWithEndpoint(path, mapping.endpoint())) {
                return isReadOnly(method) ? mapping.readPermission() : mapping.writePermission();
            }
        }

        return null;
    }

    private String resolveSettingsPermission(String path, String method) {
        String base = RESTMappingV1.PANEL_SETTINGS;

        for (String suffix : SETTINGS_PUNISHMENT_PATHS) {
            if (startsWithEndpoint(path, base + suffix)) {
                return isReadOnly(method) ? "admin.settings.view.punishments" : "admin.settings.modify.punishments";
            }
        }

        if (startsWithEndpoint(path, base + "/domain")) {
            return isReadOnly(method) ? "admin.settings.view.domain" : "admin.settings.modify.domain";
        }

        return isReadOnly(method) ? "admin.settings.view" : "admin.settings.modify";
    }

    private static boolean isReadOnly(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static boolean startsWithEndpoint(String path, String endpoint) {
        return path.equals(endpoint) || path.startsWith(endpoint + "/");
    }

    private record PermissionMapping(String endpoint, String readPermission, String writePermission) {
        PermissionMapping(String endpoint, String permission) {
            this(endpoint, permission, permission);
        }
    }
}
