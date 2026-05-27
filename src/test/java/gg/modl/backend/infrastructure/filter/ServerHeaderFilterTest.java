package gg.modl.backend.infrastructure.filter;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.server.ServerService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class ServerHeaderFilterTest {

    @Test
    void ignoresForwardedHostByDefault() throws Exception {
        ServerService serverService = mock(ServerService.class);
        Server hostServer = new Server("Host", "host", "server_host", "admin@example.com", true, ServerPlan.FREE);
        when(serverService.getServerFromDomain("host.example")).thenReturn(hostServer);

        ServerHeaderFilter filter = new ServerHeaderFilter(serverService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/panel/profile");
        request.addHeader("Host", "host.example");
        request.addHeader("X-Forwarded-Host", "victim.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(serverService).getServerFromDomain(eq("host.example"));
        verify(serverService, never()).getServerFromDomain(eq("victim.example"));
    }
}
