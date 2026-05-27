package gg.modl.backend.staff.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.InvitationMongoRepository;
import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.PunishmentMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffRoleMongoRepository;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.staff.data.Staff;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaffServiceRoleSecurityTest {

    @Test
    void memberManagerCannotAssignHigherRole() {
        StaffMongoRepository staffRepository = mock(StaffMongoRepository.class);
        StaffRoleMongoRepository roleRepository = mock(StaffRoleMongoRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        StaffService service = new StaffService(
            mock(InvitationMongoRepository.class),
            staffRepository,
            roleRepository,
            mock(PlayerMongoRepository.class),
            mock(PunishmentMongoRepository.class),
            mock(ServerMongoRepository.class),
            mock(PlayerService.class),
            permissionService,
            mock(ServerTimestampService.class)
        );
        Server server = server();
        Staff target = Staff.builder()
            .id("staff-id")
            .email("target@example.com")
            .username("target")
            .role("Helper")
            .build();
        when(staffRepository.findById(server, "staff-id")).thenReturn(Optional.of(target));
        when(permissionService.getRoleByName(server, "Helper")).thenReturn(Optional.of(role("helper", "Helper", 3)));
        when(permissionService.getRoleByName(server, "Admin")).thenReturn(Optional.of(role("admin", "Admin", 1)));

        assertThrows(
            ForbiddenException.class,
            () -> service.updateStaffRole(server, "staff-id", "Admin", "actor@example.com", "Helper")
        );
    }

    private StaffRole role(String id, String name, int order) {
        return StaffRole.builder()
            .id(id)
            .name(name)
            .order(order)
            .permissions(List.of("admin.staff.manage.members"))
            .build();
    }

    private Server server() {
        Server server = new Server("Server", "server", "server_db", "owner@example.com", true, ServerPlan.FREE);
        server.setId("server-id");
        return server;
    }
}
