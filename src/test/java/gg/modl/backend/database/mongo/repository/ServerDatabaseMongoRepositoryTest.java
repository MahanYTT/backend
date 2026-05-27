package gg.modl.backend.database.mongo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class ServerDatabaseMongoRepositoryTest {
    @Test
    void missingPlayersCollectionIsSafe() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        MongoTemplate template = mock(MongoTemplate.class);
        Server server = server("server_demo");
        when(tenantMongoAccess.forServer(server)).thenReturn(template);
        when(template.collectionExists(CollectionName.PLAYERS)).thenReturn(false);

        ServerDatabaseMongoRepository repository = new ServerDatabaseMongoRepository(tenantMongoAccess);

        ServerDatabaseMongoRepository.PlayerCollectionInspection result = repository.inspectPlayersCollection(server);

        assertThat(result.status()).isEqualTo(ServerDatabaseMongoRepository.PlayerCollectionInspection.Status.SAFE_EMPTY);
        assertThat(result.players()).isZero();
    }

    @Test
    void existingEmptyPlayersCollectionIsSafe() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        MongoTemplate template = mock(MongoTemplate.class);
        Server server = server("server_demo");
        when(tenantMongoAccess.forServer(server)).thenReturn(template);
        when(template.collectionExists(CollectionName.PLAYERS)).thenReturn(true);
        when(template.count(new Query(), CollectionName.PLAYERS)).thenReturn(0L);

        ServerDatabaseMongoRepository repository = new ServerDatabaseMongoRepository(tenantMongoAccess);

        ServerDatabaseMongoRepository.PlayerCollectionInspection result = repository.inspectPlayersCollection(server);

        assertThat(result.status()).isEqualTo(ServerDatabaseMongoRepository.PlayerCollectionInspection.Status.SAFE_EMPTY);
        assertThat(result.players()).isZero();
    }

    @Test
    void existingNonEmptyPlayersCollectionIsProtected() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        MongoTemplate template = mock(MongoTemplate.class);
        Server server = server("server_demo");
        when(tenantMongoAccess.forServer(server)).thenReturn(template);
        when(template.collectionExists(CollectionName.PLAYERS)).thenReturn(true);
        when(template.count(new Query(), CollectionName.PLAYERS)).thenReturn(2L);

        ServerDatabaseMongoRepository repository = new ServerDatabaseMongoRepository(tenantMongoAccess);

        ServerDatabaseMongoRepository.PlayerCollectionInspection result = repository.inspectPlayersCollection(server);

        assertThat(result.status()).isEqualTo(ServerDatabaseMongoRepository.PlayerCollectionInspection.Status.BLOCKED_NON_EMPTY);
        assertThat(result.players()).isEqualTo(2);
    }

    @Test
    void blankDatabaseNameIsUnknownAndDoesNotOpenTenantDatabase() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        Server server = server(" ");

        ServerDatabaseMongoRepository repository = new ServerDatabaseMongoRepository(tenantMongoAccess);

        ServerDatabaseMongoRepository.PlayerCollectionInspection result = repository.inspectPlayersCollection(server);

        assertThat(result.status()).isEqualTo(ServerDatabaseMongoRepository.PlayerCollectionInspection.Status.UNKNOWN_ERROR);
        verifyNoInteractions(tenantMongoAccess);
    }

    @Test
    void nonServerPrefixedDatabaseNameIsUnknownAndDoesNotOpenTenantDatabase() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        Server server = server("modl");

        ServerDatabaseMongoRepository repository = new ServerDatabaseMongoRepository(tenantMongoAccess);

        ServerDatabaseMongoRepository.PlayerCollectionInspection result = repository.inspectPlayersCollection(server);

        assertThat(result.status()).isEqualTo(ServerDatabaseMongoRepository.PlayerCollectionInspection.Status.UNKNOWN_ERROR);
        assertThat(result.reason()).isEqualTo("unsafe_database_name");
        verifyNoInteractions(tenantMongoAccess);
    }

    @Test
    void dropDatabaseRefusesNonServerPrefixedDatabaseName() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        Server server = server("not_server_demo");

        ServerDatabaseMongoRepository repository = new ServerDatabaseMongoRepository(tenantMongoAccess);

        boolean dropped = repository.dropDatabase(server);

        assertThat(dropped).isFalse();
        verify(tenantMongoAccess, never()).forServer(server);
    }

    @Test
    void inspectionExceptionIsUnknown() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        Server server = server("server_demo");
        when(tenantMongoAccess.forServer(server)).thenThrow(new IllegalStateException("no database"));

        ServerDatabaseMongoRepository repository = new ServerDatabaseMongoRepository(tenantMongoAccess);

        ServerDatabaseMongoRepository.PlayerCollectionInspection result = repository.inspectPlayersCollection(server);

        assertThat(result.status()).isEqualTo(ServerDatabaseMongoRepository.PlayerCollectionInspection.Status.UNKNOWN_ERROR);
    }

    private Server server(String databaseName) {
        return new Server("Demo", "demo", databaseName, "admin@example.com", false, ServerPlan.FREE);
    }
}
