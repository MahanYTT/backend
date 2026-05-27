package gg.modl.backend.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.config.ModlCorsProperties;
import gg.modl.backend.server.data.ProvisioningStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.service.ServerProvisioningService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServerServiceTest {
    @Test
    void verifyEmailTokenUsesAtomicRepositoryClaimBeforeProvisioning() {
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        ServerProvisioningService provisioningService = mock(ServerProvisioningService.class);
        ModlCorsProperties corsProperties = new ModlCorsProperties();
        ServerService serverService = new ServerService(serverRepository, provisioningService, corsProperties);
        Server verified = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);
        verified.setProvisioningStatus(ProvisioningStatus.COMPLETED);
        when(serverRepository.verifyEmailTokenAtomically("token")).thenReturn(Optional.of(verified));

        Server result = serverService.verifyEmailToken("token");

        assertThat(result).isSameAs(verified);
        verify(serverRepository).verifyEmailTokenAtomically("token");
        verify(serverRepository, never()).findByEmailVerificationToken("token");
        verify(serverRepository, never()).saveEntity(verified);
        verify(provisioningService).provision(verified);
    }
}
