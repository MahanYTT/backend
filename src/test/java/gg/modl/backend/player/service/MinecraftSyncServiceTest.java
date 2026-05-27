package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.MigrationMongoRepository;
import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerInstanceSnapshotMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.proto.modl.v1.SyncResponse;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MinecraftSyncServiceTest {

    @Mock
    private PlayerMongoRepository playerRepository;

    @Mock
    private StaffMongoRepository staffRepository;

    @Mock
    private ServerMongoRepository serverRepository;

    @Mock
    private MigrationMongoRepository migrationRepository;

    @Mock
    private ServerInstanceSnapshotMongoRepository serverInstanceSnapshotRepository;

    @Mock
    private PlayerStatusCalculator statusCalculator;

    @Mock
    private PunishmentTypeService punishmentTypeService;

    @Mock
    private PunishmentLifecycleService punishmentLifecycleService;

    @Mock
    private MinecraftChatLogService minecraftChatLogService;

    @Mock
    private IssuerNameResolver issuerNameResolver;

    @Mock
    private SyncStaffEventService syncStaffEventService;

    @Mock
    private SyncActiveStaffService syncActiveStaffService;

    private MinecraftSyncService minecraftSyncService;

    @BeforeEach
    void setUp() {
        minecraftSyncService = new MinecraftSyncService(
            playerRepository,
            staffRepository,
            serverRepository,
            migrationRepository,
            serverInstanceSnapshotRepository,
            statusCalculator,
            punishmentTypeService,
            punishmentLifecycleService,
            minecraftChatLogService,
            issuerNameResolver,
            syncStaffEventService,
            syncActiveStaffService
        );
        lenient().when(syncStaffEventService.collectStaffEvents(any(), any(), any())).thenReturn(List.of());
        lenient().when(syncActiveStaffService.getActiveStaffMembers(any(), any())).thenReturn(List.of());
    }

    @Test
    void syncReturnsEnvelopeWhenNoPlayersAreOnline() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);

        when(punishmentTypeService.getPunishmentTypes(server)).thenReturn(List.of());

        SyncResponse response = minecraftSyncService.sync(
            server,
            "2025-01-01T00:00:00Z",
            List.of(),
            "lobby",
            List.of(),
            List.of(),
            null,
            null
        );

        assertNotNull(response.getTimestamp());
        assertTrue(response.hasData());
        assertNotNull(response.getData().getPendingPunishmentsList());
        assertNotNull(response.getData().getStaffNotificationsList());
        assertFalse(response.getData().hasMigrationTask());
    }

    @SuppressWarnings("unchecked")
    @Test
    void syncLowercasesOnlineUuidsBeforeQueryingRepositories() {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);

        when(punishmentTypeService.getPunishmentTypes(server)).thenReturn(List.of());
        when(playerRepository.findByMinecraftUuids(eq(server), any(Collection.class))).thenReturn(List.of());

        minecraftSyncService.sync(
            server,
            "2025-01-01T00:00:00Z",
            List.of(new MinecraftSyncService.OnlinePlayerInput("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "PlayerOne", "1.2.3.4")),
            "lobby",
            List.of(),
            List.of(),
            null,
            null
        );

        ArgumentCaptor<Collection<String>> uuidsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(playerRepository).findByMinecraftUuids(eq(server), uuidsCaptor.capture());
        assertTrue(uuidsCaptor.getValue().contains("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
    }
}
