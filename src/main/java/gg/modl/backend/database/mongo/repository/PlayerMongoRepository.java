package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.AbstractServerMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.PlayerFields;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.server.data.Server;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class PlayerMongoRepository extends AbstractServerMongoRepository<Player> {
    public PlayerMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(Player.class, CollectionName.PLAYERS, tenantMongoAccess);
    }

    public Optional<Player> findByMinecraftUuid(Server server, UUID minecraftUuid) {
        return findByMinecraftUuid(server, minecraftUuid.toString());
    }

    public Optional<Player> findByMinecraftUuid(Server server, String minecraftUuid) {
        return findOne(server, Query.query(Criteria.where(PlayerFields.MINECRAFT_UUID).is(minecraftUuid)));
    }

    public Optional<Player> findByMinecraftUuid(String databaseName, String minecraftUuid) {
        return findOne(databaseName, Query.query(Criteria.where(PlayerFields.MINECRAFT_UUID).is(minecraftUuid)));
    }

    public Optional<Player> findByUsernameIgnoreCase(Server server, String username) {
        String escapedUsername = Pattern.quote(username.trim());
        Query query = Query.query(Criteria.where(PlayerFields.USERNAME).regex("^" + escapedUsername + "$", "i"));
        return findOne(server, query);
    }

    public List<Player> searchByUsernamePattern(Server server, String searchTerm, int limit) {
        Pattern pattern = Pattern.compile(Pattern.quote(searchTerm), Pattern.CASE_INSENSITIVE);
        Query query = Query.query(Criteria.where(PlayerFields.USERNAME).regex(pattern));
        query.limit(limit);
        return find(server, query);
    }

    public List<Player> findOnlinePlayers(Server server, int limit) {
        Query query = Query.query(Criteria.where(PlayerFields.DATA_IS_ONLINE).is(true));
        query.limit(limit);
        return find(server, query);
    }

    private static final int DEFAULT_QUERY_LIMIT = 1000;

    public List<Player> findByMinecraftUuids(Server server, Collection<String> minecraftUuids) {
        return findByMinecraftUuids(server, minecraftUuids, DEFAULT_QUERY_LIMIT);
    }

    public List<Player> findByMinecraftUuids(Server server, Collection<String> minecraftUuids, int limit) {
        if (minecraftUuids == null || minecraftUuids.isEmpty()) {
            return List.of();
        }

        Query query = Query.query(Criteria.where(PlayerFields.MINECRAFT_UUID).in(minecraftUuids));
        query.limit(limit);
        return find(server, query);
    }

    public List<Player> findByIpAddresses(Server server, Collection<String> ipAddresses) {
        return findByIpAddresses(server, ipAddresses, DEFAULT_QUERY_LIMIT);
    }

    public List<Player> findByIpAddresses(Server server, Collection<String> ipAddresses, int limit) {
        if (ipAddresses == null || ipAddresses.isEmpty()) {
            return List.of();
        }

        Query query = Query.query(Criteria.where(PlayerFields.IP_ADDRESS).in(ipAddresses));
        query.limit(limit);
        return find(server, query);
    }

    public List<Player> findByIpAddressesExcludingUuid(Server server, Collection<String> ipAddresses, String excludedUuid, int limit) {
        if (ipAddresses == null || ipAddresses.isEmpty()) {
            return List.of();
        }

        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(PlayerFields.IP_ADDRESS).in(ipAddresses),
            Criteria.where(PlayerFields.MINECRAFT_UUID).ne(excludedUuid)
        ));
        query.limit(limit);
        return find(server, query);
    }

    public List<Player> findAvailablePlayers(Server server, Collection<String> assignedUuids, int limit) {
        Query query = new Query();
        if (assignedUuids != null && !assignedUuids.isEmpty()) {
            query.addCriteria(Criteria.where(PlayerFields.MINECRAFT_UUID).nin(assignedUuids));
        }
        query.limit(limit);
        return find(server, query);
    }

    public void updateLoginState(Server server, Player player) {
        Update update = new Update()
            .set(PlayerFields.USERNAMES, player.getUsernames())
            .set(PlayerFields.IP_ADDRESSES, player.getIpAddresses())
            .set(PlayerFields.DATA, player.getData());
        updateById(server, player.getId(), update);
    }

    private void updateById(Server server, String playerId, Update update) {
        Query query = Query.query(Criteria.where(PlayerFields.ID).is(playerId));
        updateFirst(server, query, update);
    }

    public void replaceUsernames(Server server, Player player) {
        updateById(server, player.getId(), new Update().set(PlayerFields.USERNAMES, player.getUsernames()));
    }

    public void replaceNotes(Server server, Player player) {
        updateById(server, player.getId(), new Update().set(PlayerFields.NOTES, player.getNotes()));
    }

    public void replaceIpAddresses(Server server, Player player) {
        updateById(server, player.getId(), new Update().set(PlayerFields.IP_ADDRESSES, player.getIpAddresses()));
    }

    public void replacePendingNotifications(Server server, Player player, List<Map<String, Object>> notifications) {
        updateById(server, player.getId(), new Update().set(PlayerFields.DATA_PENDING_NOTIFICATIONS, notifications));
    }

    public void replaceData(Server server, Player player) {
        updateById(server, player.getId(), new Update().set(PlayerFields.DATA, player.getData()));
    }

    public void replaceLinkedAccounts(Server server, Player player) {
        Map<String, Object> data = player.getData();
        Object linkedAccounts = data != null ? data.get("linkedAccounts") : null;
        Object lastLinkedUpdate = data != null ? data.get("lastLinkedUpdate") : null;

        Update update = new Update().set(PlayerFields.DATA_LINKED_ACCOUNTS, linkedAccounts);
        if (lastLinkedUpdate instanceof Date date) {
            update.set(PlayerFields.DATA_LAST_LINKED_UPDATE, date);
        } else {
            update.unset(PlayerFields.DATA_LAST_LINKED_UPDATE);
        }
        updateById(server, player.getId(), update);
    }

    public boolean markDisconnected(Server server, String minecraftUuid, long sessionDurationMs) {
        Query query = Query.query(Criteria.where(PlayerFields.MINECRAFT_UUID).is(minecraftUuid));
        Update update = new Update()
            .set(PlayerFields.DATA_IS_ONLINE, false)
            .set(PlayerFields.DATA_LAST_LOGOUT, new Date());

        if (sessionDurationMs > 0) {
            update.inc(PlayerFields.DATA_TOTAL_PLAYTIME_SECONDS, sessionDurationMs / 1000);
        }

        return updateFirst(server, query, update).getMatchedCount() > 0;
    }

    public boolean updateLastServer(Server server, String minecraftUuid, String serverName) {
        Query query = Query.query(Criteria.where(PlayerFields.MINECRAFT_UUID).is(minecraftUuid));
        Update update = new Update().set(PlayerFields.DATA_LAST_SERVER, serverName);
        return updateFirst(server, query, update).getMatchedCount() > 0;
    }

    public void markStalePlayersOffline(Server server, Collection<String> onlineUuids,
                                        String serverName, Date logoutTime) {
        Criteria criteria = Criteria.where(PlayerFields.DATA_IS_ONLINE).is(true)
            .and(PlayerFields.MINECRAFT_UUID).nin(onlineUuids);
        if (serverName != null && !serverName.isBlank()) {
            criteria = criteria.and(PlayerFields.DATA_LAST_SERVER).is(serverName);
        }
        Update update = new Update()
            .set(PlayerFields.DATA_IS_ONLINE, false)
            .set(PlayerFields.DATA_LAST_LOGOUT, logoutTime);
        updateMulti(server, Query.query(criteria), update);
    }

    public List<Player> findByMinecraftUuids(Server server, List<UUID> minecraftUuids) {
        if (minecraftUuids == null || minecraftUuids.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(Criteria.where(PlayerFields.MINECRAFT_UUID).in(minecraftUuids));
        query.limit(DEFAULT_QUERY_LIMIT);
        return find(server, query);
    }

    public void insertAll(Server server, List<Player> players) {
        serverTemplate(server).insertAll(players);
    }

    public BulkOperations bulkOps(Server server) {
        return serverTemplate(server).bulkOps(
            BulkOperations.BulkMode.UNORDERED,
            Player.class,
            CollectionName.PLAYERS
        );
    }

    public void bulkMergeByUuid(Server server, Map<UUID, Update> updatesByUuid) {
        if (updatesByUuid == null || updatesByUuid.isEmpty()) {
            return;
        }
        BulkOperations ops = bulkOps(server);
        for (Map.Entry<UUID, Update> entry : updatesByUuid.entrySet()) {
            ops.updateOne(
                Query.query(Criteria.where(PlayerFields.MINECRAFT_UUID).is(entry.getKey())),
                entry.getValue()
            );
        }
        ops.execute();
    }

    public long countOnlinePlayers(Server server) {
        return count(server, Query.query(Criteria.where(PlayerFields.DATA_IS_ONLINE).is(true)));
    }

    public long countAll(Server server) {
        return count(server, new Query());
    }

    public long countOnlineByUuids(Server server, Collection<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(PlayerFields.MINECRAFT_UUID).in(uuids),
            Criteria.where(PlayerFields.DATA_IS_ONLINE).is(true)
        ));
        return count(server, query);
    }
}
