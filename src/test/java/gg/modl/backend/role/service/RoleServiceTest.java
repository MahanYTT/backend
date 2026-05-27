package gg.modl.backend.role.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffRoleMongoRepository;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.service.ServerTimestampService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Test
    void defaultTicketRolesIncludeAppealModifyPermission() {
        StaffRoleMongoRepository roleRepository = mock(StaffRoleMongoRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = new RoleService(
            roleRepository,
            mock(StaffMongoRepository.class),
            permissionService,
            mock(ServerTimestampService.class)
        );
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        when(permissionService.getPunishmentPermissions(server)).thenReturn(List.of());
        when(permissionService.getAllPermissionIds(server)).thenReturn(List.of(
            "ticket.view.all",
            "ticket.reply.all",
            "appeal.modify"
        ));

        roleService.createDefaultRoles(server);

        ArgumentCaptor<StaffRole> captor = ArgumentCaptor.forClass(StaffRole.class);
        verify(roleRepository, org.mockito.Mockito.times(4)).upsertRole(org.mockito.Mockito.eq(server), captor.capture());
        for (StaffRole role : captor.getAllValues()) {
            if (!"super-admin".equals(role.getId())) {
                assertTrue(role.getPermissions().contains("appeal.modify"), role.getId());
            }
        }
    }
}
