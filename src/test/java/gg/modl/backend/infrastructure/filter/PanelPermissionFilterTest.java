package gg.modl.backend.infrastructure.filter;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffService;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class PanelPermissionFilterTest {

    @Test
    void dashboardAlertsAreReadableByAuthenticatedPanelUsersWithoutDashboardPermission() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);
        StaffService staffService = mock(StaffService.class);
        PanelPermissionFilter filter = new PanelPermissionFilter(permissionService, staffService);
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", RESTMappingV1.PANEL_DASHBOARD + "/alerts");
        request.setAttribute(RequestAttribute.SERVER, server);
        AuthSessionData session = new AuthSessionData();
        session.setEmail("staff@example.com");
        request.setAttribute(RequestAttribute.SESSION, session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        lenient().when(permissionService.isSuperAdmin(server, "staff@example.com")).thenReturn(false);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(staffService, never()).getStaffByEmail(server, "staff@example.com");
    }

    @Test
    void otherDashboardRoutesStillRequireDashboardPermission() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);
        StaffService staffService = mock(StaffService.class);
        PanelPermissionFilter filter = new PanelPermissionFilter(permissionService, staffService);
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", RESTMappingV1.PANEL_DASHBOARD);
        request.setAttribute(RequestAttribute.SERVER, server);
        AuthSessionData session = new AuthSessionData();
        session.setEmail("staff@example.com");
        request.setAttribute(RequestAttribute.SESSION, session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        Staff staff = Staff.builder().email("staff@example.com").role("Helper").build();
        when(permissionService.isSuperAdmin(server, "staff@example.com")).thenReturn(false);
        when(staffService.getStaffByEmail(server, "staff@example.com")).thenReturn(Optional.of(staff));
        when(permissionService.hasPermission(server, "Helper", "admin.audit.view.dashboard")).thenReturn(false);

        filter.doFilter(request, response, chain);

        org.junit.jupiter.api.Assertions.assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void dashboardAlertWritesStillRequireDashboardPermission() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);
        StaffService staffService = mock(StaffService.class);
        PanelPermissionFilter filter = new PanelPermissionFilter(permissionService, staffService);
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", RESTMappingV1.PANEL_DASHBOARD + "/alerts");
        request.setAttribute(RequestAttribute.SERVER, server);
        AuthSessionData session = new AuthSessionData();
        session.setEmail("staff@example.com");
        request.setAttribute(RequestAttribute.SESSION, session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        Staff staff = Staff.builder().email("staff@example.com").role("Helper").build();
        when(permissionService.isSuperAdmin(server, "staff@example.com")).thenReturn(false);
        when(staffService.getStaffByEmail(server, "staff@example.com")).thenReturn(Optional.of(staff));
        when(permissionService.hasPermission(server, "Helper", "admin.audit.view.dashboard")).thenReturn(false);

        filter.doFilter(request, response, chain);

        org.junit.jupiter.api.Assertions.assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void appealWritesRemainCompatibleWithTicketReplyAllPermission() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);
        StaffService staffService = mock(StaffService.class);
        PanelPermissionFilter filter = new PanelPermissionFilter(permissionService, staffService);
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", RESTMappingV1.PANEL_APPEALS + "/appeal-1/replies");
        request.setAttribute(RequestAttribute.SERVER, server);
        AuthSessionData session = new AuthSessionData();
        session.setEmail("staff@example.com");
        request.setAttribute(RequestAttribute.SESSION, session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        Staff staff = Staff.builder().email("staff@example.com").role("Helper").build();
        when(permissionService.isSuperAdmin(server, "staff@example.com")).thenReturn(false);
        when(staffService.getStaffByEmail(server, "staff@example.com")).thenReturn(Optional.of(staff));
        when(permissionService.hasPermission(server, "Helper", "appeal.modify")).thenReturn(false);
        when(permissionService.hasPermission(server, "Helper", "ticket.reply.all")).thenReturn(true);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void ticketReplyAllDoesNotPermitAppealStatusWrites() throws Exception {
        PermissionService permissionService = mock(PermissionService.class);
        StaffService staffService = mock(StaffService.class);
        PanelPermissionFilter filter = new PanelPermissionFilter(permissionService, staffService);
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", RESTMappingV1.PANEL_APPEALS + "/appeal-1/status");
        request.setAttribute(RequestAttribute.SERVER, server);
        AuthSessionData session = new AuthSessionData();
        session.setEmail("staff@example.com");
        request.setAttribute(RequestAttribute.SESSION, session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        Staff staff = Staff.builder().email("staff@example.com").role("Helper").build();
        when(permissionService.isSuperAdmin(server, "staff@example.com")).thenReturn(false);
        when(staffService.getStaffByEmail(server, "staff@example.com")).thenReturn(Optional.of(staff));
        when(permissionService.hasPermission(server, "Helper", "appeal.modify")).thenReturn(false);

        filter.doFilter(request, response, chain);

        org.junit.jupiter.api.Assertions.assertEquals(403, response.getStatus());
    }
}
