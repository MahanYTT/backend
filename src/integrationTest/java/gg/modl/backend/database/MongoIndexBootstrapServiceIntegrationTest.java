package gg.modl.backend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.support.AbstractMongoIntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that {@link MongoIndexBootstrapService} actually creates the indexes it declares.
 * The unit test suite mocks {@code MongoTemplate} and so cannot detect missing or misconfigured
 * indexes, TTL settings, partial filters, or compound key ordering — all of which only surface
 * against a real Mongo instance.
 *
 * <p>Tenant indexes are exercised here because the public {@code createTenantIndexes} entry
 * point is invokable without going through Spring lifecycle ({@code @PostConstruct} on the
 * global path).
 */
@DataMongoTest
@Testcontainers
class MongoIndexBootstrapServiceIntegrationTest extends AbstractMongoIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    private MongoIndexBootstrapService bootstrap;

    @BeforeEach
    void setUp() {
        TenantMongoAccess tenantAccess = mock(TenantMongoAccess.class);
        when(tenantAccess.forServer(any())).thenReturn(mongoTemplate);
        when(tenantAccess.forDatabase(any())).thenReturn(mongoTemplate);
        when(tenantAccess.global()).thenReturn(mongoTemplate);

        ServerMongoRepository serverRepo = mock(ServerMongoRepository.class);
        when(serverRepo.findAll()).thenReturn(List.of());

        bootstrap = new MongoIndexBootstrapService(tenantAccess, serverRepo);
        for (String collection : mongoTemplate.getCollectionNames()) {
            mongoTemplate.dropCollection(collection);
        }
    }

    @Test
    void createTenantIndexes_createsPlayerUuidIndexAsUniqueSparse() {
        bootstrap.createTenantIndexes(mongoTemplate);

        IndexInfo uuidIndex = findIndex(CollectionName.PLAYERS, "uidx_players_minecraftUuid");
        assertThat(uuidIndex.isUnique()).isTrue();
        assertThat(uuidIndex.isSparse()).isTrue();
        assertThat(uuidIndex.getIndexFields()).hasSize(1);
        assertThat(uuidIndex.getIndexFields().get(0).getKey()).isEqualTo("minecraftUuid");
        assertThat(uuidIndex.getIndexFields().get(0).getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void createTenantIndexes_createsCompoundDescendingIndexInOrder() {
        bootstrap.createTenantIndexes(mongoTemplate);

        IndexInfo issuerIndex = findIndex(CollectionName.PLAYERS, "idx_players_punishments_issuerName_issued_desc");
        assertThat(issuerIndex.getIndexFields()).hasSize(2);
        assertThat(issuerIndex.getIndexFields().get(0).getKey()).isEqualTo("punishments.issuerName");
        assertThat(issuerIndex.getIndexFields().get(0).getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(issuerIndex.getIndexFields().get(1).getKey()).isEqualTo("punishments.issued");
        assertThat(issuerIndex.getIndexFields().get(1).getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void createTenantIndexes_createsTtlIndexWithZeroExpiry() {
        bootstrap.createTenantIndexes(mongoTemplate);

        IndexInfo ttlIndex = findIndex(CollectionName.SESSIONS, "idx_sessions_expiresAt_ttl");
        assertThat(ttlIndex.getExpireAfter()).isPresent();
        assertThat(ttlIndex.getExpireAfter().get()).isEqualTo(Duration.ZERO);
    }

    @Test
    void createTenantIndexes_isIdempotent() {
        bootstrap.createTenantIndexes(mongoTemplate);
        List<IndexInfo> afterFirst = mongoTemplate.indexOps(CollectionName.PLAYERS).getIndexInfo();

        bootstrap.createTenantIndexes(mongoTemplate);
        List<IndexInfo> afterSecond = mongoTemplate.indexOps(CollectionName.PLAYERS).getIndexInfo();

        assertThat(afterSecond).hasSameSizeAs(afterFirst);
    }

    @Test
    void createTenantIndexes_createsStaffUniqueIndexes() {
        bootstrap.createTenantIndexes(mongoTemplate);

        IndexInfo emailIndex = findIndex(CollectionName.STAFF, "uidx_staff_email");
        IndexInfo usernameIndex = findIndex(CollectionName.STAFF, "uidx_staff_username");
        assertThat(emailIndex.isUnique()).isTrue();
        assertThat(usernameIndex.isUnique()).isTrue();
    }

    private IndexInfo findIndex(String collection, String name) {
        return mongoTemplate.indexOps(collection).getIndexInfo().stream()
            .filter(i -> name.equals(i.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected index " + name + " on collection " + collection));
    }
}
