package gg.modl.backend.dashboard.controller.v3;

import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.backend.dashboard.service.DashboardService;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.MinecraftDashboardResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard")
@RequiredArgsConstructor
public class MinecraftDashboardV3Controller {
    private final DashboardService dashboardService;

    @GetMapping(
        value = "/stats",
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<MinecraftDashboardResponse> getDashboardStats(HttpServletRequest httpRequest) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        MinecraftDashboardStatsResponse stats = dashboardService.getMinecraftStats(server);

        return ResponseEntity.ok(MinecraftDashboardProtoMapper.toMinecraftDashboardResponse(stats));
    }
}
