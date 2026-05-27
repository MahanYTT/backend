package gg.modl.backend.registration.cleanup;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.ServerDatabaseMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.registration.config.RegistrationCleanupProperties;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.ServerService;
import gg.modl.backend.server.data.ProvisioningStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.staff.service.StaffService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegistrationCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    @Mock private ServerMongoRepository serverRepository;
    @Mock private ServerDatabaseMongoRepository serverDatabaseRepository;
    @Mock private ServerService serverService;
    @Mock private PermissionService permissionService;
    @Mock private StaffService staffService;
    private RegistrationCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        RegistrationCleanupProperties properties = new RegistrationCleanupProperties();
        properties.setEnabled(true);
        properties.setDryRun(false);
        properties.setExpiry(Duration.ofHours(24));
        properties.setBatchSize(100);

        cleanupService = new RegistrationCleanupService(
            properties,
            serverRepository,
            serverDatabaseRepository,
            serverService,
            permissionService,
            staffService,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void dropsTenantDatabaseBeforeDeletingGlobalServerRecord() {
        Server candidate = expiredCandidate();
        Server claimed = expiredCandidate();
        claimed.setCleanupClaimId("claim-1");

        when(serverRepository.findExpiredRegistrationCleanupCandidates(Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), 100))
            .thenReturn(List.of(candidate));
        when(serverDatabaseRepository.inspectPlayersCollection(candidate))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.safeMissing());
        when(serverRepository.claimExpiredRegistrationForCleanup(candidate.getId(), Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), NOW))
            .thenReturn(Optional.of(claimed));
        when(serverDatabaseRepository.inspectPlayersCollection(claimed))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.safeEmpty());
        when(serverRepository.confirmRegistrationCleanupClaim(claimed.getId(), claimed.getCleanupClaimId(), Date.from(NOW.minus(Duration.ofHours(24))), NOW))
            .thenReturn(Optional.of(claimed));
        when(serverDatabaseRepository.dropDatabase(claimed)).thenReturn(true);
        when(serverRepository.deleteClaimedExpiredRegistration(claimed.getId(), claimed.getCleanupClaimId(), Date.from(NOW.minus(Duration.ofHours(24)))))
            .thenReturn(true);

        cleanupService.runCleanupOnce();

        InOrder order = inOrder(serverDatabaseRepository, serverRepository, serverService, permissionService, staffService);
        order.verify(serverRepository).claimExpiredRegistrationForCleanup(candidate.getId(), Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), NOW);
        order.verify(serverRepository).confirmRegistrationCleanupClaim(claimed.getId(), claimed.getCleanupClaimId(), Date.from(NOW.minus(Duration.ofHours(24))), NOW);
        order.verify(serverDatabaseRepository).dropDatabase(claimed);
        order.verify(serverRepository).deleteClaimedExpiredRegistration(claimed.getId(), claimed.getCleanupClaimId(), Date.from(NOW.minus(Duration.ofHours(24))));
        order.verify(serverService).evictAllServerCaches();
        order.verify(permissionService).evictPermissionCache();
        order.verify(staffService).evictAllStaffCaches();
    }

    @Test
    void keepsGlobalServerRecordWhenTenantDatabaseDropFails() {
        Server candidate = expiredCandidate();
        Server claimed = expiredCandidate();
        claimed.setCleanupClaimId("claim-1");

        when(serverRepository.findExpiredRegistrationCleanupCandidates(Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), 100))
            .thenReturn(List.of(candidate));
        when(serverDatabaseRepository.inspectPlayersCollection(candidate))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.safeEmpty());
        when(serverRepository.claimExpiredRegistrationForCleanup(candidate.getId(), Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), NOW))
            .thenReturn(Optional.of(claimed));
        when(serverDatabaseRepository.inspectPlayersCollection(claimed))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.safeEmpty());
        when(serverRepository.confirmRegistrationCleanupClaim(claimed.getId(), claimed.getCleanupClaimId(), Date.from(NOW.minus(Duration.ofHours(24))), NOW))
            .thenReturn(Optional.of(claimed));
        when(serverDatabaseRepository.dropDatabase(claimed)).thenReturn(false);

        cleanupService.runCleanupOnce();

        verify(serverRepository, never()).deleteClaimedExpiredRegistration(claimed.getId(), claimed.getCleanupClaimId(), Date.from(NOW.minus(Duration.ofHours(24))));
        verifyNoInteractions(serverService, permissionService, staffService);
    }

    @Test
    void doesNotDropDatabaseWhenClaimFails() {
        Server candidate = expiredCandidate();

        when(serverRepository.findExpiredRegistrationCleanupCandidates(Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), 100))
            .thenReturn(List.of(candidate));
        when(serverDatabaseRepository.inspectPlayersCollection(candidate))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.safeEmpty());
        when(serverRepository.claimExpiredRegistrationForCleanup(candidate.getId(), Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), NOW))
            .thenReturn(Optional.empty());

        cleanupService.runCleanupOnce();

        verify(serverDatabaseRepository, never()).dropDatabase(candidate);
        verifyNoInteractions(serverService, permissionService, staffService);
    }

    @Test
    void skipsCandidateWhenPlayersCollectionIsProtected() {
        Server candidate = expiredCandidate();

        when(serverRepository.findExpiredRegistrationCleanupCandidates(Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), 100))
            .thenReturn(List.of(candidate));
        when(serverDatabaseRepository.inspectPlayersCollection(candidate))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.blockedNonEmpty(3));

        cleanupService.runCleanupOnce();

        verify(serverRepository, never()).claimExpiredRegistrationForCleanup(candidate.getId(), Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), NOW);
        verify(serverDatabaseRepository, never()).dropDatabase(candidate);
    }

    @Test
    void releasesClaimWhenPostClaimInspectionBecomesProtected() {
        Server candidate = expiredCandidate();
        Server claimed = expiredCandidate();
        claimed.setCleanupClaimId("claim-1");

        when(serverRepository.findExpiredRegistrationCleanupCandidates(Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), 100))
            .thenReturn(List.of(candidate));
        when(serverDatabaseRepository.inspectPlayersCollection(candidate))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.safeEmpty());
        when(serverRepository.claimExpiredRegistrationForCleanup(candidate.getId(), Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), NOW))
            .thenReturn(Optional.of(claimed));
        when(serverDatabaseRepository.inspectPlayersCollection(claimed))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.blockedNonEmpty(1));

        cleanupService.runCleanupOnce();

        verify(serverRepository).releaseRegistrationCleanupClaim(claimed.getId(), claimed.getCleanupClaimId());
        verify(serverDatabaseRepository, never()).dropDatabase(claimed);
    }

    @Test
    void doesNotDropDatabaseWhenClaimIsLostBeforeDestructiveWork() {
        Server candidate = expiredCandidate();
        Server claimed = expiredCandidate();
        claimed.setCleanupClaimId("claim-1");

        when(serverRepository.findExpiredRegistrationCleanupCandidates(Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), 100))
            .thenReturn(List.of(candidate));
        when(serverDatabaseRepository.inspectPlayersCollection(candidate))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.safeEmpty());
        when(serverRepository.claimExpiredRegistrationForCleanup(candidate.getId(), Date.from(NOW.minus(Duration.ofHours(24))), Date.from(NOW.minus(Duration.ofHours(6))), NOW))
            .thenReturn(Optional.of(claimed));
        when(serverDatabaseRepository.inspectPlayersCollection(claimed))
            .thenReturn(ServerDatabaseMongoRepository.PlayerCollectionInspection.safeEmpty());
        when(serverRepository.confirmRegistrationCleanupClaim(claimed.getId(), claimed.getCleanupClaimId(), Date.from(NOW.minus(Duration.ofHours(24))), NOW))
            .thenReturn(Optional.empty());

        cleanupService.runCleanupOnce();

        verify(serverDatabaseRepository, never()).dropDatabase(claimed);
        verify(serverRepository, never()).deleteClaimedExpiredRegistration(claimed.getId(), claimed.getCleanupClaimId(), Date.from(NOW.minus(Duration.ofHours(24))));
        verifyNoInteractions(serverService, permissionService, staffService);
    }

    private Server expiredCandidate() {
        Server server = new Server("Demo", "demo", "server_demo", "admin@example.com", false, ServerPlan.FREE);
        server.setId("server-id");
        server.setProvisioningStatus(ProvisioningStatus.PENDING);
        server.setEmailVerificationToken("token");
        server.setCreatedAt(Date.from(NOW.minus(Duration.ofHours(25))));
        server.setUpdatedAt(Date.from(NOW.minus(Duration.ofHours(25))));
        return server;
    }
}
