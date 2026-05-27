package gg.modl.backend.dashboard.controller;

import gg.modl.backend.dashboard.dto.response.DashboardMetricsResponse;
import gg.modl.backend.dashboard.service.DashboardService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PREFIX_PANEL)
@RequiredArgsConstructor
public class PanelStatsAliasController {
    private final DashboardService dashboardService;

    @GetMapping("/stats")
    public ResponseEntity<DashboardMetricsResponse> getStats(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        return ResponseEntity.ok(dashboardService.getMetrics(server));
    }
}
