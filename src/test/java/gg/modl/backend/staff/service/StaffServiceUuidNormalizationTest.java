package gg.modl.backend.staff.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.InvitationMongoRepository;
import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.PunishmentMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffRoleMongoRepository;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.request.AssignMinecraftPlayerRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaffServiceUuidNormalizationTest {

    @Test
    void markStaffDisconnectedLowercasesUuidBeforeQueryingRepository() {
        StaffMongoRepository staffRepository = mock(StaffMongoRepository.class);
        StaffService service = buildService(staffRepository);

        Server server = server();
        service.markStaffDisconnected(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE");

        verify(staffRepository).updateLastSeenByAssignedMinecraftUuid(server, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }

    @Test
    void assignMinecraftPlayerLowercasesUuidBeforeQueryingPlayerRepository() {
        StaffMongoRepository staffRepository = mock(StaffMongoRepository.class);
        PlayerMongoRepository playerRepository = mock(PlayerMongoRepository.class);
        StaffService service = buildService(staffRepository, playerRepository);

        Server server = server();
        Staff staff = Staff.builder()
            .id("staff-id")
            .email("target@example.com")
            .username("target")
            .role("Helper")
            .build();
        Player player = Player.builder()
            .minecraftUuid(UUID.randomUUID())
            .usernames(new ArrayList<>(List.of(new UsernameEntry("PlayerOne", new java.util.Date()))))
            .build();

        when(staffRepository.findByUsername(server, "target")).thenReturn(Optional.of(staff));
        when(playerRepository.findByMinecraftUuid(eq(server), eq("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
            .thenReturn(Optional.of(player));
        when(staffRepository.findByAssignedMinecraftUuidExcludingId(any(), any(), any())).thenReturn(Optional.empty());
        when(staffRepository.saveEntity(any(Server.class), any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(1));

        service.assignMinecraftPlayer(server, "target",
            new AssignMinecraftPlayerRequest("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", null));

        verify(playerRepository).findByMinecraftUuid(server, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }

    private StaffService buildService(StaffMongoRepository staffRepository) {
        return buildService(staffRepository, mock(PlayerMongoRepository.class));
    }

    private StaffService buildService(StaffMongoRepository staffRepository, PlayerMongoRepository playerRepository) {
        return new StaffService(
            mock(InvitationMongoRepository.class),
            staffRepository,
            mock(StaffRoleMongoRepository.class),
            playerRepository,
            mock(PunishmentMongoRepository.class),
            mock(ServerMongoRepository.class),
            mock(PlayerService.class),
            mock(PermissionService.class),
            mock(ServerTimestampService.class)
        );
    }

    private Server server() {
        Server server = new Server("Server", "server", "server_db", "owner@example.com", true, ServerPlan.FREE);
        server.setId("server-id");
        return server;
    }
}
