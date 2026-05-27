package gg.modl.backend.dashboard.controller.v1;

import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.backend.dashboard.service.DashboardService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_DASHBOARD)
@RequiredArgsConstructor
public class MinecraftDashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats(HttpServletRequest httpRequest) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftDashboardStatsResponse stats = dashboardService.getMinecraftStats(server);

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "stats", stats
        ));
    }
}
