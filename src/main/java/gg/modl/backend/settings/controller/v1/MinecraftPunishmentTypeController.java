package gg.modl.backend.settings.controller.v1;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_PUNISHMENTS)
@RequiredArgsConstructor
public class MinecraftPunishmentTypeController {
    private final PunishmentTypeService punishmentTypeService;

    @GetMapping("/types")
    public ResponseEntity<Map<String, Object>> getPunishmentTypes(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "data", types
        ));
    }
}
