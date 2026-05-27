package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.AuditLogFields;
import gg.modl.backend.database.mongo.fields.PlayerFields;
import gg.modl.backend.database.mongo.fields.PunishmentFields;
import gg.modl.backend.database.mongo.fields.StaffFields;
import gg.modl.backend.database.mongo.fields.TicketFields;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuditMongoRepository {
    private final TenantMongoAccess tenantMongoAccess;
    private static final String TOTAL_ACTIONS = "totalActions";
    private static final String COUNT = "count";
    private static final String LEVEL_MODERATION = "moderation";
    private static final String LEVEL_INFO = "info";
    private static final String SOURCE_SYSTEM = "system";
    private static final String STATUS_UNSTARTED = PunishmentStatus.UNSTARTED;
    private static final String DATE = "date";
    private static final String ALIAS_TICKET_ACTIONS = "ticketActions";
    private static final String ALIAS_MODERATION_ACTIONS = "moderationActions";
    private static final String ALIAS_LAST_ACTIVE = "lastActive";
    private static final String ALIAS_EFFECTIVE_ISSUER = "effectiveIssuer";
    private static final String ALIAS_PUNISHMENT_ID = "punishmentId";
    private static final String ALIAS_PLAYER_ID = "playerId";
    private static final String ALIAS_TYPE_ORDINAL = "typeOrdinal";
    private static final String ALIAS_ISSUER_NAME = "issuerName";
    private static final String ALIAS_ISSUER_ID = "issuerId";
    private static final String ALIAS_ISSUED = "issued";
    private static final String ALIAS_STARTED = "started";
    private static final String ALIAS_DATA = "data";
    private static final String ALIAS_MODIFICATIONS = "modifications";
    private static final String ALIAS_EVIDENCE = "evidence";
    private static final String ALIAS_ATTACHED_TICKET_IDS = "attachedTicketIds";
    private static final String ALIAS_USERNAMES = "usernames";
    private static final String ALIAS_REASON = "reason";
    private static final String ALIAS_DURATION = "duration";
    private static final String ALIAS_SUBJECT = "subject";
    private static final String ALIAS_CATEGORY = "category";
    private static final String ALIAS_STATUS = "status";
    private static final String ALIAS_TICKET_CREATED = "ticketCreated";
    private static final String ALIAS_LAST_ACTIVITY = "lastActivity";
    private static final String ALIAS_FIRST_REPLY = "firstReply";

    public List<Staff> findAllStaff(Server server) {
        Query query = new Query();
        query.fields().include(StaffFields.ID, StaffFields.USERNAME, StaffFields.EMAIL, StaffFields.ROLE, StaffFields.ASSIGNED_MINECRAFT_USERNAME, StaffFields.UPDATED_AT);
        return tenantMongoAccess.forServer(server).find(query, Staff.class, CollectionName.STAFF);
    }

    public List<StaffActivityResult> aggregateLogActivityBySource(Server server, Date startDate) {
        Criteria logCriteria = Criteria.where(AuditLogFields.SOURCE).ne(SOURCE_SYSTEM);
        if (startDate != null) {
            logCriteria = logCriteria.and(AuditLogFields.CREATED).gte(startDate);
        }

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(logCriteria),
            Aggregation.group(AuditLogFields.SOURCE)
                .count().as(TOTAL_ACTIONS)
                .sum(ConditionalOperators.when(Criteria.where(AuditLogFields.DESCRIPTION).regex(Pattern.compile("ticket", Pattern.CASE_INSENSITIVE)))
                    .then(1)
                    .otherwise(0)).as(ALIAS_TICKET_ACTIONS)
                .sum(ConditionalOperators.when(new Criteria().orOperator(
                    Criteria.where(AuditLogFields.LEVEL).is(LEVEL_MODERATION),
                    Criteria.where(AuditLogFields.DESCRIPTION).regex(Pattern.compile("ban|mute|kick|punishment", Pattern.CASE_INSENSITIVE))
                )).then(1).otherwise(0)).as(ALIAS_MODERATION_ACTIONS)
                .max(AuditLogFields.CREATED).as(ALIAS_LAST_ACTIVE),
            Aggregation.sort(Sort.Direction.DESC, TOTAL_ACTIONS)
        );

        return tenantMongoAccess.forServer(server)
            .aggregate(aggregation, CollectionName.LOGS, StaffActivityResult.class)
            .getMappedResults();
    }

    public List<IdCountResult> aggregateTicketResponseCounts(Server server, Date startDate) {
        Criteria replyCriteria = Criteria.where(TicketFields.REPLY_STAFF).is(true);
        if (startDate != null) {
            replyCriteria = replyCriteria.and(TicketFields.REPLY_CREATED).gte(startDate);
        }

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.unwind(TicketFields.REPLIES),
            Aggregation.match(replyCriteria),
            Aggregation.group(TicketFields.REPLY_NAME).count().as(COUNT)
        );

        return tenantMongoAccess.forServer(server)
            .aggregate(aggregation, CollectionName.TICKETS, IdCountResult.class)
            .getMappedResults();
    }

    public List<IdCountResult> aggregatePunishmentCountsByIssuer(Server server, Date startDate) {
        List<AggregationOperation> stages = new ArrayList<>();
        stages.add(Aggregation.unwind(PlayerFields.PUNISHMENTS));
        if (startDate != null) {
            stages.add(Aggregation.match(Criteria.where(PlayerFields.PUNISHMENT_ISSUED).gte(startDate)));
        }
        stages.add(context -> new Document("$addFields", new Document(ALIAS_EFFECTIVE_ISSUER,
            new Document("$ifNull", List.of("$" + PlayerFields.PUNISHMENT_ISSUER_ID, "$" + PlayerFields.PUNISHMENT_ISSUER_NAME)))));
        stages.add(Aggregation.group(ALIAS_EFFECTIVE_ISSUER).count().as(COUNT));

        return tenantMongoAccess.forServer(server)
            .aggregate(Aggregation.newAggregation(stages), CollectionName.PLAYERS, IdCountResult.class)
            .getMappedResults();
    }

    public Map<String, String> mapStaffUsernamesByIds(Server server, Set<String> staffIds) {
        if (staffIds == null || staffIds.isEmpty()) {
            return Map.of();
        }

        Query query = Query.query(Criteria.where(StaffFields.ID).in(staffIds));
        query.fields().include(StaffFields.ID, StaffFields.USERNAME);
        List<Staff> staffMembers = tenantMongoAccess.forServer(server).find(query, Staff.class, CollectionName.STAFF);
        Map<String, String> usernamesById = new HashMap<>();
        for (Staff staff : staffMembers) {
            if (staff.getId() != null && staff.getUsername() != null) {
                usernamesById.put(staff.getId(), staff.getUsername());
            }
        }
        return usernamesById;
    }

    public List<AuditLog> findPunishmentLogs(Server server, Date startDate, int limit, boolean canRollbackOnly) {
        Criteria criteria = Criteria.where(AuditLogFields.CREATED).gte(startDate)
            .orOperator(
                Criteria.where(AuditLogFields.LEVEL).is(LEVEL_MODERATION),
                Criteria.where(AuditLogFields.DESCRIPTION).regex(Pattern.compile("ban|mute|kick|warn", Pattern.CASE_INSENSITIVE))
            );

        if (canRollbackOnly) {
            criteria = criteria.and(AuditLogFields.METADATA_CAN_ROLLBACK).ne(false);
        }

        Query query = Query.query(criteria)
            .with(Sort.by(Sort.Direction.DESC, AuditLogFields.CREATED))
            .limit(limit);

        return tenantMongoAccess.forServer(server).find(query, AuditLog.class, CollectionName.LOGS);
    }

    public List<Document> aggregatePunishmentRows(Server server) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where(PlayerFields.PUNISHMENTS).exists(true).ne(List.of())),
            Aggregation.unwind(PlayerFields.PUNISHMENTS),
            Aggregation.match(Criteria.where(PlayerFields.PUNISHMENT_TYPE_ORDINAL).ne(0)
                .and(PlayerFields.PUNISHMENT_DATA_STATUS).ne(STATUS_UNSTARTED)),
            Aggregation.sort(Sort.Direction.DESC, PlayerFields.PUNISHMENT_ISSUED),
            Aggregation.project()
                .and(PlayerFields.PUNISHMENT_ID).as(ALIAS_PUNISHMENT_ID)
                .and(PlayerFields.MINECRAFT_UUID).as(ALIAS_PLAYER_ID)
                .and(PlayerFields.PUNISHMENT_TYPE_ORDINAL).as(ALIAS_TYPE_ORDINAL)
                .and(PlayerFields.PUNISHMENT_ISSUER_NAME).as(ALIAS_ISSUER_NAME)
                .and(PlayerFields.PUNISHMENT_ISSUER_ID).as(ALIAS_ISSUER_ID)
                .and(PlayerFields.PUNISHMENT_ISSUED).as(ALIAS_ISSUED)
                .and("punishments." + PunishmentFields.STARTED).as(ALIAS_STARTED)
                .and("punishments." + PunishmentFields.DATA).as(ALIAS_DATA)
                .and("punishments." + PunishmentFields.MODIFICATIONS).as(ALIAS_MODIFICATIONS)
                .and("punishments." + PunishmentFields.EVIDENCE).as(ALIAS_EVIDENCE)
                .and("punishments." + PunishmentFields.ATTACHED_TICKET_IDS).as(ALIAS_ATTACHED_TICKET_IDS)
                .and(PlayerFields.USERNAMES).as(ALIAS_USERNAMES)
        );

        return tenantMongoAccess.forServer(server)
            .aggregate(aggregation, CollectionName.PLAYERS, Document.class)
            .getMappedResults();
    }

    public AuditLog findAuditLogById(Server server, String logId) {
        return tenantMongoAccess.forServer(server)
            .findOne(Query.query(Criteria.where(AuditLogFields.ID).is(logId)), AuditLog.class, CollectionName.LOGS);
    }

    public void saveAuditLog(Server server, AuditLog auditLog) {
        tenantMongoAccess.forServer(server).save(auditLog, CollectionName.LOGS);
    }

    public void markAuditLogRolledBack(Server server, String logId, String performerUsername, Date rollbackDate) {
        Update update = new Update()
            .set(AuditLogFields.METADATA_ROLLED_BACK, true)
            .set(AuditLogFields.METADATA_ROLLBACK_DATE, rollbackDate)
            .set(AuditLogFields.METADATA_ROLLBACK_BY, performerUsername);
        tenantMongoAccess.forServer(server).updateFirst(
            Query.query(Criteria.where(AuditLogFields.ID).is(logId)),
            update,
            AuditLog.class,
            CollectionName.LOGS
        );
    }

    public List<Document> readTable(Server server, String collectionName, int limit, int skip) {
        Query query = new Query()
            .with(Sort.by(Sort.Direction.DESC, "_id"))
            .skip(skip)
            .limit(limit);
        return tenantMongoAccess.forServer(server).find(query, Document.class, collectionName);
    }

    public long countCollection(Server server, String collectionName) {
        return tenantMongoAccess.forServer(server).count(new Query(), collectionName);
    }

    public List<Document> findPlayersForRollback(Server server, String staffUsername, String staffId) {
        List<Criteria> issuerMatch = new ArrayList<>();
        issuerMatch.add(Criteria.where(PunishmentFields.ISSUER_NAME).regex("^" + Pattern.quote(staffUsername) + "$", "i"));
        if (staffId != null) {
            issuerMatch.add(Criteria.where(PunishmentFields.ISSUER_ID).is(staffId));
        }

        Query query = Query.query(Criteria.where(PlayerFields.PUNISHMENTS).elemMatch(
            new Criteria().orOperator(issuerMatch.toArray(new Criteria[0]))
        ));
        query.fields().include(PlayerFields.ID, PlayerFields.MINECRAFT_UUID, PlayerFields.PUNISHMENTS);

        return tenantMongoAccess.forServer(server).find(query, Document.class, CollectionName.PLAYERS);
    }

    public void appendRollbackModification(Server server, String playerId, String punishmentId, Map<String, Object> rollbackModification) {
        Update update = new Update().push(PlayerFields.PUNISHMENT_MODIFICATIONS, rollbackModification);
        Query query = Query.query(Criteria.where(PlayerFields.ID).is(playerId).and(PlayerFields.PUNISHMENT_ID).is(punishmentId));
        tenantMongoAccess.forServer(server).updateFirst(query, update, CollectionName.PLAYERS);
    }

    public List<Document> findPlayersForBulkAction(Server server, List<Integer> typeOrdinals) {
        Query query = Query.query(Criteria.where(PlayerFields.PUNISHMENTS).elemMatch(
            Criteria.where(PunishmentFields.TYPE_ORDINAL).in(typeOrdinals)
        ));
        query.fields().include(PlayerFields.ID, PlayerFields.MINECRAFT_UUID,
            PlayerFields.USERNAMES, PlayerFields.PUNISHMENTS);
        return tenantMongoAccess.forServer(server).find(query, Document.class, CollectionName.PLAYERS);
    }

    public void appendPunishmentModificationWithData(Server server, String playerId, String punishmentId,
            Map<String, Object> modification, Map<String, Object> dataUpdates) {
        Update update = new Update().push(PlayerFields.PUNISHMENT_MODIFICATIONS, modification);
        for (Map.Entry<String, Object> entry : dataUpdates.entrySet()) {
            update.set("punishments.$.data." + entry.getKey(), entry.getValue());
        }
        Query query = Query.query(Criteria.where(PlayerFields.ID).is(playerId).and(PlayerFields.PUNISHMENT_ID).is(punishmentId));
        tenantMongoAccess.forServer(server).updateFirst(query, update, CollectionName.PLAYERS);
    }

    public List<Document> aggregatePunishmentDetails(Server server, List<String> usernames, String staffId, Date startDate) {
        Criteria criteria = buildIssuerCriteria(usernames, staffId);
        if (startDate != null) {
            criteria = criteria.and(PlayerFields.PUNISHMENT_ISSUED).gte(startDate);
        }

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.unwind(PlayerFields.PUNISHMENTS),
            Aggregation.match(criteria),
            Aggregation.sort(Sort.Direction.DESC, PlayerFields.PUNISHMENT_ISSUED),
            Aggregation.limit(50),
            Aggregation.project()
                .and(PlayerFields.PUNISHMENT_ID).as(ALIAS_PUNISHMENT_ID)
                .and(PlayerFields.MINECRAFT_UUID).as(ALIAS_PLAYER_ID)
                .and(PlayerFields.PUNISHMENT_TYPE_ORDINAL).as(ALIAS_TYPE_ORDINAL)
                .and(PlayerFields.PUNISHMENT_ISSUED).as(ALIAS_ISSUED)
                .and("punishments." + PunishmentFields.STARTED).as(ALIAS_STARTED)
                .and(PlayerFields.PUNISHMENT_DATA_REASON).as(ALIAS_REASON)
                .and(PlayerFields.PUNISHMENT_DATA_DURATION).as(ALIAS_DURATION)
                .and("punishments." + PunishmentFields.MODIFICATIONS).as(ALIAS_MODIFICATIONS)
                .and(PlayerFields.USERNAMES).as(ALIAS_USERNAMES)
        );

        return tenantMongoAccess.forServer(server)
            .aggregate(aggregation, CollectionName.PLAYERS, Document.class)
            .getMappedResults();
    }

    private Criteria buildIssuerCriteria(List<String> usernames, String staffId) {
        List<Criteria> parts = new ArrayList<>();
        for (String username : usernames) {
            parts.add(Criteria.where(PlayerFields.PUNISHMENT_ISSUER_NAME).regex("^" + Pattern.quote(username) + "$", "i"));
        }
        if (staffId != null) {
            parts.add(Criteria.where(PlayerFields.PUNISHMENT_ISSUER_ID).is(staffId));
        }
        return new Criteria().orOperator(parts.toArray(new Criteria[0]));
    }

    public List<Document> aggregateTicketDetails(Server server, String username, Date startDate) {
        Criteria criteria = Criteria.where(TicketFields.REPLY_STAFF).is(true)
            .and(TicketFields.REPLY_NAME).regex("^" + Pattern.quote(username) + "$", "i");
        if (startDate != null) {
            criteria = criteria.and(TicketFields.REPLY_CREATED).gte(startDate);
        }

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.unwind(TicketFields.REPLIES),
            Aggregation.match(criteria),
            Aggregation.sort(Sort.Direction.DESC, TicketFields.REPLY_CREATED),
            Aggregation.group(TicketFields.ID)
                .first(TicketFields.SUBJECT).as(ALIAS_SUBJECT)
                .first(TicketFields.TYPE).as(ALIAS_CATEGORY)
                .first(TicketFields.STATUS).as(ALIAS_STATUS)
                .first(TicketFields.CREATED).as(ALIAS_TICKET_CREATED)
                .max(TicketFields.REPLY_CREATED).as(ALIAS_LAST_ACTIVITY)
                .min(TicketFields.REPLY_CREATED).as(ALIAS_FIRST_REPLY),
            Aggregation.limit(50)
        );

        return tenantMongoAccess.forServer(server)
            .aggregate(aggregation, CollectionName.TICKETS, Document.class)
            .getMappedResults();
    }

    public List<IdCountResult> aggregateDailyPunishmentCounts(Server server, List<String> usernames, String staffId, Date startDate) {
        Criteria criteria = buildIssuerCriteria(usernames, staffId);
        if (startDate != null) {
            criteria = criteria.and(PlayerFields.PUNISHMENT_ISSUED).gte(startDate);
        }

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.unwind(PlayerFields.PUNISHMENTS),
            Aggregation.match(criteria),
            Aggregation.project().andExpression("dateToString('%Y-%m-%d', " + PlayerFields.PUNISHMENT_ISSUED + ")").as(DATE),
            Aggregation.group(DATE).count().as(COUNT)
        );

        return tenantMongoAccess.forServer(server)
            .aggregate(aggregation, CollectionName.PLAYERS, IdCountResult.class)
            .getMappedResults();
    }

    public List<IdCountResult> aggregateDailyTicketResponseCounts(Server server, String username, Date startDate) {
        Criteria criteria = Criteria.where(TicketFields.REPLY_STAFF).is(true)
            .and(TicketFields.REPLY_NAME).regex("^" + Pattern.quote(username) + "$", "i");
        if (startDate != null) {
            criteria = criteria.and(TicketFields.REPLY_CREATED).gte(startDate);
        }

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.unwind(TicketFields.REPLIES),
            Aggregation.match(criteria),
            Aggregation.project().andExpression("dateToString('%Y-%m-%d', " + TicketFields.REPLY_CREATED + ")").as(DATE),
            Aggregation.group(DATE).count().as(COUNT)
        );

        return tenantMongoAccess.forServer(server)
            .aggregate(aggregation, CollectionName.TICKETS, IdCountResult.class)
            .getMappedResults();
    }

    public List<OrdinalCountResult> aggregatePunishmentTypeBreakdown(Server server, List<String> usernames, String staffId, Date startDate) {
        Criteria criteria = buildIssuerCriteria(usernames, staffId);
        if (startDate != null) {
            criteria = criteria.and(PlayerFields.PUNISHMENT_ISSUED).gte(startDate);
        }

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.unwind(PlayerFields.PUNISHMENTS),
            Aggregation.match(criteria),
            Aggregation.group(PlayerFields.PUNISHMENT_TYPE_ORDINAL).count().as(COUNT),
            Aggregation.sort(Sort.Direction.DESC, COUNT)
        );

        return tenantMongoAccess.forServer(server)
            .aggregate(aggregation, CollectionName.PLAYERS, OrdinalCountResult.class)
            .getMappedResults();
    }

    public long countEvidenceUploads(Server server, String username, Date startDate) {
        Criteria baseCriteria = Criteria.where(AuditLogFields.SOURCE).is(username);
        if (startDate != null) {
            baseCriteria = baseCriteria.and(AuditLogFields.CREATED).gte(startDate);
        }

        Query query = Query.query(
            baseCriteria.orOperator(
                Criteria.where(AuditLogFields.DESCRIPTION).regex(Pattern.compile("evidence|upload|file", Pattern.CASE_INSENSITIVE)),
                Criteria.where(AuditLogFields.LEVEL)
                    .is(LEVEL_INFO)
                    .and(AuditLogFields.DESCRIPTION)
                    .regex(Pattern.compile("uploaded|attachment", Pattern.CASE_INSENSITIVE))
            )
        );

        return tenantMongoAccess.forServer(server).count(query, AuditLog.class, CollectionName.LOGS);
    }

    public record IdCountResult(String id, int count) {}

    public record OrdinalCountResult(Integer id, int count) {}

    public record StaffActivityResult(String id, int totalActions, int ticketActions, int moderationActions, Date lastActive) {}
}
