package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.AbstractServerMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.PlayerFields;
import gg.modl.backend.database.mongo.fields.PunishmentFields;
import gg.modl.backend.infrastructure.util.MongoKeyUtils;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.server.data.Server;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class PunishmentMongoRepository extends AbstractServerMongoRepository<Player> {
    public PunishmentMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(Player.class, CollectionName.PLAYERS, tenantMongoAccess);
    }

    public Optional<Player> findByPunishmentId(Server server, String punishmentId) {
        return findOne(server, Query.query(Criteria.where(PlayerFields.PUNISHMENT_ID).is(punishmentId)));
    }

    public List<Player> findByLinkedBanId(Server server, String parentPunishmentId) {
        return find(server, linkedBanQuery(parentPunishmentId));
    }

    public List<Player> findByLinkedBanId(String databaseName, String parentPunishmentId) {
        return find(databaseName, linkedBanQuery(parentPunishmentId));
    }

    private Query linkedBanQuery(String parentPunishmentId) {
        return Query.query(Criteria.where(PlayerFields.PUNISHMENTS).elemMatch(
            Criteria.where(PunishmentFields.TYPE_ORDINAL).is(4)
                .and(PunishmentFields.DATA_LINKED_BAN_ID).is(parentPunishmentId)
        ));
    }

    public void replacePunishments(Server server, Player player) {
        Query query = Query.query(Criteria.where(PlayerFields.MINECRAFT_UUID).is(player.getMinecraftUuid().toString()));
        Update update = new Update().set(PlayerFields.PUNISHMENTS, player.getPunishments());
        updateFirst(server, query, update);
    }

    public void replacePunishments(String databaseName, Player player) {
        Query query = Query.query(Criteria.where(PlayerFields.MINECRAFT_UUID).is(player.getMinecraftUuid().toString()));
        Update update = new Update().set(PlayerFields.PUNISHMENTS, player.getPunishments());
        updateFirst(databaseName, query, update);
    }

    public void linkAppealToPunishment(Server server, String playerUuid, String punishmentId,
                                       String appealId, PunishmentNote note) {
        Query query = punishmentQuery(playerUuid, punishmentId);
        Update update = new Update()
            .push("punishments.$.attachedTicketIds", appealId)
            .push("punishments.$.notes", note);
        updateFirst(server, query, update);
    }

    public void addPunishmentNote(Server server, String playerUuid, String punishmentId,
                                  PunishmentNote note, Map<String, Object> dataUpdates) {
        Query query = punishmentQuery(playerUuid, punishmentId);
        Update update = new Update().push("punishments.$.notes", note);
        if (dataUpdates != null) {
            for (Map.Entry<String, Object> entry : dataUpdates.entrySet()) {
                MongoKeyUtils.validateUpdatePath(entry.getKey());
                update.set("punishments.$." + entry.getKey(), MongoKeyUtils.sanitizeValue(entry.getValue()));
            }
        }
        updateFirst(server, query, update);
    }

    public void applyAppealApproval(Server server, String playerUuid, String punishmentId,
                                    PunishmentModification modification, PunishmentNote note,
                                    String appealOutcome, String appealTicketId) {
        Query query = punishmentQuery(playerUuid, punishmentId);
        Update update = new Update()
            .push("punishments.$.modifications", modification)
            .push("punishments.$.notes", note)
            .set("punishments.$.data.appealOutcome", appealOutcome)
            .set("punishments.$.data.appealTicketId", appealTicketId);
        updateFirst(server, query, update);
    }

    private Query punishmentQuery(String playerUuid, String punishmentId) {
        return Query.query(
            Criteria.where("minecraftUuid").is(playerUuid)
                .and("punishments.id").is(punishmentId)
        );
    }

    public List<Document> fetchRecentPunishmentRows(Server server, Date issuedAfter, int limit) {
        Criteria criteria = Criteria.where(PlayerFields.PUNISHMENT_ISSUED).gte(issuedAfter);
        return fetchPunishmentRowsByCriteria(server, criteria, limit);
    }

    public List<Document> fetchRecentPunishmentRowsByIssuer(Server server, String issuerName, Date issuedAfter, int limit) {
        Criteria criteria = Criteria.where(PlayerFields.PUNISHMENT_ISSUER_NAME).is(issuerName)
            .and(PlayerFields.PUNISHMENT_ISSUED).gte(issuedAfter);
        return fetchPunishmentRowsByCriteria(server, criteria, limit);
    }

    private List<Document> fetchPunishmentRowsByCriteria(Server server, Criteria punishmentCriteria, int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(punishmentCriteria),
            Aggregation.unwind(PlayerFields.PUNISHMENTS),
            Aggregation.match(punishmentCriteria),
            Aggregation.sort(Sort.by(Sort.Direction.DESC, PlayerFields.PUNISHMENT_ISSUED)),
            Aggregation.limit(limit),
            Aggregation.project(PlayerFields.MINECRAFT_UUID, PlayerFields.USERNAMES)
                .and(PlayerFields.PUNISHMENTS).as("punishment")
        );
        return aggregate(server, aggregation, Document.class).getMappedResults();
    }

    public Punishment readPunishment(Server server, Document punishmentDocument) {
        return serverTemplate(server).getConverter().read(Punishment.class, punishmentDocument);
    }

    public Map<String, Integer> countPunishmentsByIssuerName(Server server) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where(PlayerFields.PUNISHMENTS).exists(true).ne(List.of())),
            Aggregation.unwind(PlayerFields.PUNISHMENTS),
            Aggregation.group(PlayerFields.PUNISHMENT_ISSUER_NAME).count().as("count")
        );
        AggregationResults<Document> results = aggregate(server, aggregation, Document.class);
        Map<String, Integer> punishmentCounts = new LinkedHashMap<>();
        for (Document document : results.getMappedResults()) {
            String issuerName = document.getString("_id");
            if (issuerName != null) {
                punishmentCounts.put(issuerName, document.getInteger("count", 0));
            }
        }
        return punishmentCounts;
    }

    public List<Player> findWithPunishmentsProjected(Server server) {
        Query query = Query.query(Criteria.where(PlayerFields.PUNISHMENTS).exists(true));
        query.fields().include(PlayerFields.PUNISHMENTS);
        return find(server, query);
    }

    public List<Player> findWithPunishmentsIssuedAfter(Server server, Date cutoff) {
        return find(server, Query.query(Criteria.where(PlayerFields.PUNISHMENT_ISSUED).gte(cutoff)));
    }

    public List<Player> findWithPunishmentsIssuedAfter(Server server, Date cutoff, int limit) {
        Query query = Query.query(Criteria.where(PlayerFields.PUNISHMENTS).elemMatch(
            Criteria.where("issued").gte(cutoff)
        ));
        query.limit(limit);
        return find(server, query);
    }

    public List<Player> findPlayersWithPunishments(Server server, int limit) {
        Query query = Query.query(Criteria.where(PlayerFields.PUNISHMENTS).exists(true));
        query.limit(limit);
        return find(server, query);
    }

    public void unsetPunishmentStatus(Server server, String playerUuid, String punishmentId) {
        Query query = Query.query(
            Criteria.where(PlayerFields.MINECRAFT_UUID).is(playerUuid)
                .and("punishments.id").is(punishmentId)
        );
        Update update = new Update().unset("punishments.$.data.status");
        updateFirst(server, query, update);
    }
}
