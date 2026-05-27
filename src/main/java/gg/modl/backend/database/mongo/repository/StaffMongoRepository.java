package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.AbstractServerMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.StaffFields;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class StaffMongoRepository extends AbstractServerMongoRepository<Staff> {
    public StaffMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(Staff.class, CollectionName.STAFF, tenantMongoAccess);
    }

    public long countAll(Server server) {
        return count(server, new Query());
    }

    public Optional<Staff> findByUsername(Server server, String username) {
        return findOne(server, Query.query(Criteria.where(StaffFields.USERNAME).is(username)));
    }

    public Optional<Staff> findByEmailExact(Server server, String email) {
        return findOne(server, Query.query(Criteria.where(StaffFields.EMAIL).is(email)));
    }

    public boolean existsByUsername(Server server, String username) {
        return exists(server, Query.query(Criteria.where(StaffFields.USERNAME).is(username)));
    }

    public boolean existsByEmailOrUsername(Server server, String email, String username) {
        Query query = new Query(new Criteria().orOperator(
            Criteria.where(StaffFields.EMAIL).is(email),
            Criteria.where(StaffFields.USERNAME).is(username)
        ));
        return exists(server, query);
    }

    public boolean existsByEmailExact(Server server, String email) {
        return exists(server, Query.query(Criteria.where(StaffFields.EMAIL).is(email)));
    }

    public boolean existsByEmailIgnoreCaseExcluding(Server server, String email, String currentEmail) {
        Staff existing = findByEmailIgnoreCase(server, email).orElse(null);
        return existing != null && !existing.getEmail().equalsIgnoreCase(currentEmail);
    }

    public Optional<Staff> findByEmailIgnoreCase(Server server, String email) {
        return findOne(server, Query.query(Criteria.where(StaffFields.EMAIL)
            .is(EmailAddressUtil.normalize(email))));
    }

    public boolean existsByUsernameExcludingId(Server server, String username, String excludedStaffId) {
        Query query = Query.query(Criteria.where(StaffFields.USERNAME).is(username)
            .and(StaffFields.ID).ne(excludedStaffId));
        return exists(server, query);
    }

    public boolean deleteById(Server server, String staffId) {
        return remove(server, Query.query(Criteria.where(StaffFields.ID).is(staffId))).getDeletedCount() > 0;
    }

    public List<Staff> findAssignedMinecraftStaff(Server server) {
        Query query = Query.query(
            Criteria.where(StaffFields.ASSIGNED_MINECRAFT_UUID).exists(true).ne(null).ne("")
        );
        return find(server, query);
    }

    public Optional<Staff> findByAssignedMinecraftUuidExcludingId(Server server, String minecraftUuid, String excludedStaffId) {
        Query query = Query.query(
            Criteria.where(StaffFields.ASSIGNED_MINECRAFT_UUID).is(minecraftUuid)
                .and(StaffFields.ID).ne(excludedStaffId)
        );
        return findOne(server, query);
    }

    public boolean updateLastSeenByAssignedMinecraftUuid(Server server, String minecraftUuid) {
        Query query = Query.query(Criteria.where(StaffFields.ASSIGNED_MINECRAFT_UUID).is(minecraftUuid));
        Update update = new Update().set(StaffFields.LAST_SEEN, new Date());
        return updateFirst(server, query, update).getModifiedCount() > 0;
    }

    public int countByRoleName(Server server, String roleName) {
        return (int) count(server, Query.query(Criteria.where(StaffFields.ROLE).is(roleName)));
    }

    public Map<String, Integer> countByRoleName(Server server) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.group(StaffFields.ROLE).count().as("count")
        );
        AggregationResults<Document> results = aggregate(server, aggregation, Document.class);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Document document : results.getMappedResults()) {
            String roleName = document.getString("_id");
            if (roleName != null) {
                counts.put(roleName, document.getInteger("count", 0));
            }
        }
        return counts;
    }

    public Map<String, String> findUsernamesByIds(Server server, Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where(StaffFields.ID).in(ids));
        query.fields().include(StaffFields.USERNAME);
        Map<String, String> result = new HashMap<>();
        for (Staff staff : find(server, query)) {
            if (staff.getId() != null && staff.getUsername() != null) {
                result.put(staff.getId(), staff.getUsername());
            }
        }
        return result;
    }

    public List<Staff> findByRoleNames(Server server, Collection<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return List.of();
        }
        return find(server, Query.query(Criteria.where(StaffFields.ROLE).in(roleNames)));
    }

    public boolean createTwoFactorToken(Server server, String minecraftUuid, String token, String ip, long createdAt) {
        Query query = Query.query(Criteria.where(StaffFields.ASSIGNED_MINECRAFT_UUID).is(minecraftUuid));
        Update update = new Update();
        update.set(StaffFields.TWO_FACTOR_TOKEN, token);
        update.set(StaffFields.TWO_FACTOR_TOKEN_IP, ip);
        update.set(StaffFields.TWO_FACTOR_TOKEN_CREATED_AT, createdAt);
        return updateFirst(server, query, update).getMatchedCount() > 0;
    }

    public Optional<Staff> findByTwoFactorToken(Server server, String token) {
        return findOne(server, Query.query(Criteria.where(StaffFields.TWO_FACTOR_TOKEN).is(token)));
    }

    public boolean activateTwoFactorSession(Server server, String staffId, String token, String sessionIp, long sessionExpiresAt) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(StaffFields.ID).is(staffId),
            Criteria.where(StaffFields.TWO_FACTOR_TOKEN).is(token)
        ));
        Update update = new Update();
        update.unset(StaffFields.TWO_FACTOR_TOKEN);
        update.unset(StaffFields.TWO_FACTOR_TOKEN_CREATED_AT);
        update.set(StaffFields.TWO_FACTOR_PENDING_DELIVERY, true);
        update.set(StaffFields.TWO_FACTOR_SESSION_IP, sessionIp);
        update.unset(StaffFields.TWO_FACTOR_TOKEN_IP);
        update.set(StaffFields.TWO_FACTOR_SESSION_EXPIRES_AT, sessionExpiresAt);
        return updateFirst(server, query, update).getModifiedCount() > 0;
    }

    public boolean deactivateSubscription(Server server, String email, String ticketId) {
        Query query = Query.query(
            Criteria.where(StaffFields.EMAIL).is(email)
                .and(StaffFields.SUBSCRIBED_TICKET_TICKET_ID).is(ticketId)
                .and(StaffFields.SUBSCRIBED_TICKET_ACTIVE).is(true)
        );
        Update update = new Update().set(StaffFields.SUBSCRIBED_TICKET_POS_ACTIVE, false);
        return updateFirst(server, query, update).getModifiedCount() > 0;
    }

    public boolean markSubscriptionRead(Server server, String email, String ticketId, Date lastReadAt) {
        Query query = Query.query(
            Criteria.where(StaffFields.EMAIL).is(email)
                .and(StaffFields.SUBSCRIBED_TICKET_TICKET_ID).is(ticketId)
                .and(StaffFields.SUBSCRIBED_TICKET_ACTIVE).is(true)
        );
        Update update = new Update().set(StaffFields.SUBSCRIBED_TICKET_POS_LAST_READ_AT, lastReadAt);
        return updateFirst(server, query, update).getModifiedCount() > 0;
    }

    public void addTicketSubscription(Server server, String email, Staff.TicketSubscription subscription) {
        Query query = Query.query(Criteria.where(StaffFields.EMAIL).is(email));
        Update update = new Update().addToSet(StaffFields.SUBSCRIBED_TICKETS, subscription);
        updateFirst(server, query, update);
    }

    public List<String> findAssignedMinecraftUuids(Server server) {
        Query query = Query.query(Criteria.where(StaffFields.ASSIGNED_MINECRAFT_UUID).exists(true).ne(null).ne(""));
        query.fields().include(StaffFields.ASSIGNED_MINECRAFT_UUID);
        return find(server, query)
            .stream()
            .map(Staff::getAssignedMinecraftUuid)
            .filter(uuid -> uuid != null && !uuid.isBlank())
            .distinct()
            .toList();
    }

    public Optional<String> findUsernameByEmail(Server server, String email) {
        Query query = Query.query(Criteria.where(StaffFields.EMAIL).is(email));
        query.fields().include(StaffFields.USERNAME);
        return findOne(server, query)
            .map(Staff::getUsername)
            .filter(username -> !username.isBlank());
    }

    public List<Staff> findWithPendingTwoFactorDelivery(Server server) {
        Query query = Query.query(
            Criteria.where(StaffFields.TWO_FACTOR_PENDING_DELIVERY).is(true)
                .and(StaffFields.ASSIGNED_MINECRAFT_UUID).exists(true).ne(null).ne("")
        );
        return find(server, query);
    }

    public void clearPendingTwoFactorDelivery(Server server) {
        Query query = Query.query(
            Criteria.where(StaffFields.TWO_FACTOR_PENDING_DELIVERY).is(true)
                .and(StaffFields.ASSIGNED_MINECRAFT_UUID).exists(true).ne(null).ne("")
        );
        updateMulti(server, query, new Update().set(StaffFields.TWO_FACTOR_PENDING_DELIVERY, false));
    }

    public List<Staff> findByUsernames(Server server, Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) return List.of();
        Query query = Query.query(Criteria.where(StaffFields.USERNAME).in(usernames));
        return find(server, query);
    }
}

