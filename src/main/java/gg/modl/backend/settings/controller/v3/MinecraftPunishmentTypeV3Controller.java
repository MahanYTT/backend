package gg.modl.backend.settings.controller.v3;

import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.proto.modl.v1.PunishmentTypesResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT + "/punishments")
@RequiredArgsConstructor
public class MinecraftPunishmentTypeV3Controller {
    private final PunishmentTypeService punishmentTypeService;

    @GetMapping(value = "/types", produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)
    public ResponseEntity<PunishmentTypesResponse> getPunishmentTypes(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);

        return ResponseEntity.ok(MinecraftPunishmentTypeProtoMapper.toPunishmentTypesResponse(200, types));
    }
}
