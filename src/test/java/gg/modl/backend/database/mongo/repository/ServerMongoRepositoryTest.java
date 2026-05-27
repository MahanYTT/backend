package gg.modl.backend.database.mongo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.server.data.ProvisioningStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class ServerMongoRepositoryTest {
    @Test
    void candidateQueryScopesToExplicitRegistrationRecords() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        MongoTemplate template = mock(MongoTemplate.class);
        when(tenantMongoAccess.global()).thenReturn(template);
        when(template.find(org.mockito.ArgumentMatchers.any(Query.class), eq(Server.class), eq(CollectionName.MODL_SERVERS))).thenReturn(List.of());
        ServerMongoRepository repository = new ServerMongoRepository(tenantMongoAccess);
        Date cutoff = Date.from(Instant.parse("2026-05-01T00:00:00Z"));

        repository.findExpiredRegistrationCleanupCandidates(cutoff, 25);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(template).find(queryCaptor.capture(), eq(Server.class), eq(CollectionName.MODL_SERVERS));
        Document query = queryCaptor.getValue().getQueryObject();
        String queryText = query.toString();
        assertThat(queryText).contains("emailVerified=false");
        assertThat(queryText).contains("provisioningStatus=PENDING");
        assertThat(queryText).contains("emailVerificationToken");
        assertThat(queryText).contains("createdAt");
        assertThat(queryText).contains("databaseName");
        assertThat(queryText).contains("^server_");
    }

    @Test
    void claimRepeatsSafetyConditionsAndSetsCleanupClaim() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        MongoTemplate template = mock(MongoTemplate.class);
        Server claimed = server();
        when(tenantMongoAccess.global()).thenReturn(template);
        when(template.findAndModify(
            org.mockito.ArgumentMatchers.any(Query.class),
            org.mockito.ArgumentMatchers.any(UpdateDefinition.class),
            org.mockito.ArgumentMatchers.any(FindAndModifyOptions.class),
            eq(Server.class),
            eq(CollectionName.MODL_SERVERS)
        )).thenReturn(claimed);
        ServerMongoRepository repository = new ServerMongoRepository(tenantMongoAccess);
        Date cutoff = Date.from(Instant.parse("2026-05-01T00:00:00Z"));
        Instant claimedAt = Instant.parse("2026-05-01T01:00:00Z");

        repository.claimExpiredRegistrationForCleanup("server-id", cutoff, claimedAt);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> updateCaptor = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(template).findAndModify(
            queryCaptor.capture(),
            updateCaptor.capture(),
            org.mockito.ArgumentMatchers.any(FindAndModifyOptions.class),
            eq(Server.class),
            eq(CollectionName.MODL_SERVERS)
        );
        String queryText = queryCaptor.getValue().getQueryObject().toString();
        assertThat(queryText).contains("_id=server-id");
        assertThat(queryText).contains("emailVerified=false");
        assertThat(queryText).contains("provisioningStatus=PENDING");
        assertThat(updateCaptor.getValue().getUpdateObject().toString()).contains("cleanupClaimId");
        assertThat(updateCaptor.getValue().getUpdateObject().toString()).contains("cleanupClaimedAt");
    }

    @Test
    void finalDeleteRequiresMatchingClaimAndOriginalEligibility() {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        MongoTemplate template = mock(MongoTemplate.class);
        when(tenantMongoAccess.global()).thenReturn(template);
        when(template.remove(org.mockito.ArgumentMatchers.any(Query.class), eq(Server.class), eq(CollectionName.MODL_SERVERS)))
            .thenReturn(com.mongodb.client.result.DeleteResult.acknowledged(1));
        ServerMongoRepository repository = new ServerMongoRepository(tenantMongoAccess);
        Date cutoff = Date.from(Instant.parse("2026-05-01T00:00:00Z"));

        boolean deleted = repository.deleteClaimedExpiredRegistration("server-id", "claim-id", cutoff);

        assertThat(deleted).isTrue();
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(template).remove(queryCaptor.capture(), eq(Server.class), eq(CollectionName.MODL_SERVERS));
        String queryText = queryCaptor.getValue().getQueryObject().toString();
        assertThat(queryText).contains("cleanupClaimId=claim-id");
        assertThat(queryText).contains("emailVerified=false");
        assertThat(queryText).contains("provisioningStatus=PENDING");
    }

    private Server server() {
        Server server = new Server("Demo", "demo", "server_demo", "admin@example.com", false, ServerPlan.FREE);
        server.setId("server-id");
        server.setProvisioningStatus(ProvisioningStatus.PENDING);
        server.setEmailVerificationToken("token");
        server.setCreatedAt(Date.from(Instant.now().minus(Duration.ofHours(25))));
        return server;
    }
}
