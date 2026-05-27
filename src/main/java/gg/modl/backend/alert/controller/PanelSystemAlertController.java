package gg.modl.backend.alert.controller;

import gg.modl.backend.alert.data.SystemAlert;
import gg.modl.backend.alert.dto.response.PanelSystemAlertResponse;
import gg.modl.backend.alert.service.SystemAlertService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_DASHBOARD)
@RequiredArgsConstructor
public class PanelSystemAlertController {
    private final SystemAlertService alertService;
    private final PermissionService permissionService;

    @GetMapping("/alerts")
    public ResponseEntity<List<PanelSystemAlertResponse>> getAlerts(HttpServletRequest request) {
        final Server server = RequestUtil.getRequestServer(request);
        final String email = RequestUtil.getSessionEmail(request);
        final boolean superAdmin = email != null && permissionService.isSuperAdmin(server, email);
        final List<PanelSystemAlertResponse> response = alertService.getVisibleAlerts(superAdmin, new Date()).stream()
            .map(PanelSystemAlertResponse::from)
            .toList();
        return ResponseEntity.ok(response);
    }
}
