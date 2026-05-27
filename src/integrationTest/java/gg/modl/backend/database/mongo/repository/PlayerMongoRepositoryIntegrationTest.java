package gg.modl.backend.database.mongo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.support.AbstractMongoIntegrationTest;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Slice integration test for {@link PlayerMongoRepository}. Exercises real Mongo query semantics
 * against a Testcontainers-managed MongoDB — this catches projection, regex, $in, and update bugs
 * that mock-based unit tests cannot, while staying much faster than a full @SpringBootTest.
 *
 * <p>{@link TenantMongoAccess} is stubbed because the repository's only dependency on it is to
 * resolve a {@link MongoTemplate} per server — we provide the slice-managed template directly.
 */
@DataMongoTest
@Testcontainers
class PlayerMongoRepositoryIntegrationTest extends AbstractMongoIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    private PlayerMongoRepository repository;
    private Server server;

    @BeforeEach
    void setUp() {
        TenantMongoAccess tenantAccess = mock(TenantMongoAccess.class);
        when(tenantAccess.forServer(any())).thenReturn(mongoTemplate);
        when(tenantAccess.forDatabase(any())).thenReturn(mongoTemplate);

        server = new Server("Demo", "demo", "modl-it", "admin@example.com", true, ServerPlan.FREE);
        repository = new PlayerMongoRepository(tenantAccess);

        mongoTemplate.dropCollection(Player.class);
    }

    @Test
    void findByMinecraftUuid_returnsMatchingPlayer() {
        UUID uuid = UUID.randomUUID();
        savePlayer(uuid, "Alice");

        Optional<Player> found = repository.findByMinecraftUuid(server, uuid);

        assertThat(found).isPresent();
        assertThat(found.get().getMinecraftUuid()).isEqualTo(uuid);
    }

    @Test
    void findByMinecraftUuid_returnsEmptyWhenNoMatch() {
        savePlayer(UUID.randomUUID(), "Alice");

        Optional<Player> found = repository.findByMinecraftUuid(server, UUID.randomUUID());

        assertThat(found).isEmpty();
    }

    @Test
    void findByUsernameIgnoreCase_matchesRegardlessOfCase() {
        savePlayer(UUID.randomUUID(), "Alice");

        Optional<Player> exact = repository.findByUsernameIgnoreCase(server, "Alice");
        Optional<Player> lower = repository.findByUsernameIgnoreCase(server, "alice");
        Optional<Player> upper = repository.findByUsernameIgnoreCase(server, "ALICE");

        assertThat(exact).isPresent();
        assertThat(lower).isPresent();
        assertThat(upper).isPresent();
    }

    @Test
    void findByUsernameIgnoreCase_doesNotMatchSubstring() {
        savePlayer(UUID.randomUUID(), "AliceWonderland");

        Optional<Player> partial = repository.findByUsernameIgnoreCase(server, "Alice");

        assertThat(partial).isEmpty();
    }

    @Test
    void findByMinecraftUuids_returnsAllMatchingPlayers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        savePlayer(a, "Alice");
        savePlayer(b, "Bob");
        savePlayer(c, "Carol");

        List<Player> found = repository.findByMinecraftUuids(server, List.of(a.toString(), c.toString()));

        assertThat(found).extracting(Player::getMinecraftUuid).containsExactlyInAnyOrder(a, c);
    }

    @Test
    void findByMinecraftUuids_emptyInputReturnsEmptyList() {
        savePlayer(UUID.randomUUID(), "Alice");

        List<Player> found = repository.findByMinecraftUuids(server, List.<String>of());

        assertThat(found).isEmpty();
    }

    @Test
    void findOnlinePlayers_returnsOnlyOnlinePlayers() {
        savePlayer(UUID.randomUUID(), "Online1", true);
        savePlayer(UUID.randomUUID(), "Online2", true);
        savePlayer(UUID.randomUUID(), "Offline", false);

        List<Player> online = repository.findOnlinePlayers(server, 10);

        assertThat(online).hasSize(2);
        assertThat(online).allMatch(p -> Boolean.TRUE.equals(p.getData().get("isOnline")));
    }

    @Test
    void markDisconnected_setsOfflineAndIncrementsPlaytime() {
        UUID uuid = UUID.randomUUID();
        savePlayer(uuid, "Alice", true);

        boolean updated = repository.markDisconnected(server, uuid.toString(), 30_000L);

        assertThat(updated).isTrue();
        Player updatedPlayer = repository.findByMinecraftUuid(server, uuid).orElseThrow();
        assertThat(updatedPlayer.getData().get("isOnline")).isEqualTo(false);
        assertThat(updatedPlayer.getData().get("lastLogout")).isInstanceOf(Date.class);
        assertThat(((Number) updatedPlayer.getData().get("totalPlaytimeSeconds")).longValue()).isEqualTo(30L);
    }

    @Test
    void markDisconnected_returnsFalseWhenPlayerMissing() {
        boolean updated = repository.markDisconnected(server, UUID.randomUUID().toString(), 1000L);

        assertThat(updated).isFalse();
    }

    @Test
    void updateLoginState_persistsUsernamesIpsAndData() {
        UUID uuid = UUID.randomUUID();
        Player player = Player.builder()
            .id(uuid.toString())
            .minecraftUuid(uuid)
            .usernames(List.of(new UsernameEntry("Alice", new Date())))
            .ipAddresses(List.of())
            .data(new HashMap<>(java.util.Map.of("isOnline", true)))
            .build();
        mongoTemplate.save(player);

        player.getData().put("lastLogin", new Date());
        repository.updateLoginState(server, player);

        Player updated = repository.findByMinecraftUuid(server, uuid).orElseThrow();
        assertThat(updated.getUsernames()).extracting(UsernameEntry::username).containsExactly("Alice");
        assertThat(updated.getData()).containsKey("lastLogin");
    }

    @Test
    void countAll_returnsTotalCount() {
        savePlayer(UUID.randomUUID(), "Alice");
        savePlayer(UUID.randomUUID(), "Bob");

        assertThat(repository.countAll(server)).isEqualTo(2L);
    }

    @Test
    void countOnlinePlayers_returnsOnlyOnlineCount() {
        savePlayer(UUID.randomUUID(), "On1", true);
        savePlayer(UUID.randomUUID(), "On2", true);
        savePlayer(UUID.randomUUID(), "Off", false);

        assertThat(repository.countOnlinePlayers(server)).isEqualTo(2L);
    }

    private void savePlayer(UUID uuid, String username) {
        savePlayer(uuid, username, false);
    }

    private void savePlayer(UUID uuid, String username, boolean online) {
        Player player = Player.builder()
            .id(uuid.toString())
            .minecraftUuid(uuid)
            .usernames(List.of(new UsernameEntry(username, new Date())))
            .ipAddresses(List.of())
            .data(new HashMap<>(java.util.Map.of("isOnline", online)))
            .build();
        mongoTemplate.save(player);
    }
}
