package gg.modl.backend.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.TicketFields;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.server.data.Server;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantMigrationService {
    static final String LOWERCASE_TICKET_UUIDS_MIGRATION_ID = "lowercase-ticket-uuids";
    private static final Pattern UPPERCASE_HEX_PATTERN = Pattern.compile("[A-F]");

    private final TenantMongoAccess tenantMongoAccess;
    private final ServerMongoRepository serverRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void runTenantMigrations() {
        List<Server> servers;
        try {
            servers = serverRepository.findAll();
        } catch (Exception e) {
            log.error("Failed to list servers for tenant migrations", e);
            return;
        }

        List<Server> targets = servers.stream()
            .filter(server -> server != null
                && server.getDatabaseName() != null
                && !server.getDatabaseName().isBlank())
            .toList();

        log.info("Running tenant migrations for {} existing tenants", targets.size());
        int succeeded = 0;
        int failed = 0;
        for (Server server : targets) {
            try {
                log.debug("Running tenant migrations for server id={} database={}",
                    server.getId(), server.getDatabaseName());
                applyMigrationsForTenant(tenantMongoAccess.forServer(server));
                succeeded++;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to run tenant migrations for server id={} database={}",
                    server.getId(), server.getDatabaseName(), e);
            }
        }
        log.info("Tenant migration run complete succeeded={} failed={}", succeeded, failed);
    }

    void applyMigrationsForTenant(MongoTemplate template) {
        runMigrationOnce(template, LOWERCASE_TICKET_UUIDS_MIGRATION_ID, TenantMigrationService::lowercaseTicketUuids);
    }

    private static void runMigrationOnce(MongoTemplate template, String migrationId, MigrationStep step) {
        if (isMigrationApplied(template, migrationId)) {
            return;
        }
        step.run(template);
        markMigrationApplied(template, migrationId);
    }

    private static boolean isMigrationApplied(MongoTemplate template, String migrationId) {
        Document marker = template.getCollection(CollectionName.TENANT_MIGRATIONS)
            .find(Filters.eq("_id", migrationId))
            .first();
        return marker != null;
    }

    private static void markMigrationApplied(MongoTemplate template, String migrationId) {
        template.getCollection(CollectionName.TENANT_MIGRATIONS).updateOne(
            Filters.eq("_id", migrationId),
            Updates.set("appliedAt", new Date()),
            new UpdateOptions().upsert(true)
        );
    }

    private static void lowercaseTicketUuids(MongoTemplate template) {
        MongoCollection<Document> tickets = template.getCollection(CollectionName.TICKETS);

        Bson filter = Filters.or(
            Filters.regex(TicketFields.CREATOR_UUID, UPPERCASE_HEX_PATTERN),
            Filters.regex(TicketFields.REPORTED_PLAYER_UUID, UPPERCASE_HEX_PATTERN)
        );

        List<Bson> pipeline = List.of(new Document("$set", new Document()
            .append(TicketFields.CREATOR_UUID, conditionalLowercase(TicketFields.CREATOR_UUID))
            .append(TicketFields.REPORTED_PLAYER_UUID, conditionalLowercase(TicketFields.REPORTED_PLAYER_UUID))
        ));

        UpdateResult result = tickets.updateMany(filter, pipeline);
        log.info("Lowercased ticket UUIDs in database={} matched={} modified={}",
            template.getDb().getName(), result.getMatchedCount(), result.getModifiedCount());
    }

    private static Document conditionalLowercase(String field) {
        String fieldRef = "$" + field;
        Document isNonEmptyString = new Document("$and", List.of(
            new Document("$eq", List.of(new Document("$type", fieldRef), "string")),
            new Document("$ne", List.of(fieldRef, ""))
        ));
        return new Document("$cond", List.of(
            isNonEmptyString,
            new Document("$toLower", fieldRef),
            fieldRef
        ));
    }

    @FunctionalInterface
    private interface MigrationStep {
        void run(MongoTemplate template);
    }
}
