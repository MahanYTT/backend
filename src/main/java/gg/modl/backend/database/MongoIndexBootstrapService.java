package gg.modl.backend.database;

import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MongoIndexBootstrapService {
    private final TenantMongoAccess tenantMongoAccess;
    private final ServerMongoRepository serverRepository;

    @PostConstruct
    public void initGlobalIndexes() {
        try {
            createGlobalIndexes(tenantMongoAccess.global());
        } catch (Exception e) {
            log.error("Failed to create global database indexes", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexesForExistingTenants() {
        List<Server> servers;
        try {
            servers = serverRepository.findAll();
        } catch (Exception e) {
            log.error("Failed to list servers for tenant index bootstrap", e);
            return;
        }

        List<Server> targets = servers.stream()
            .filter(server -> server != null
                && server.getDatabaseName() != null
                && !server.getDatabaseName().isBlank())
            .toList();

        log.info("Ensuring tenant indexes for {} existing tenants", targets.size());
        int succeeded = 0;
        int failed = 0;
        for (Server server : targets) {
            try {
                log.debug("Ensuring tenant indexes for server id={} database={}",
                    server.getId(), server.getDatabaseName());
                createTenantIndexes(tenantMongoAccess.forServer(server));
                succeeded++;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to ensure tenant indexes for server id={} database={}",
                    server.getId(), server.getDatabaseName(), e);
            }
        }
        log.info("Tenant index bootstrap complete succeeded={} failed={}", succeeded, failed);
    }

    private static void createGlobalIndexes(MongoTemplate template) {
        ensureIndexes(template, CollectionName.MODL_SERVERS, List.of(
            IndexSpec.standard("uidx_servers_serverName", doc("serverName", 1), true, false),
            IndexSpec.standard("uidx_servers_customDomain", doc("customDomain", 1), true, false),
            IndexSpec.standard("uidx_servers_adminEmail", doc("adminEmail", 1), true, false),
            IndexSpec.standard("idx_servers_emailVerified", doc("emailVerified", 1), false, false),
            IndexSpec.standard("uidx_servers_emailVerificationToken", doc("emailVerificationToken", 1), true, true),
            IndexSpec.standard("idx_servers_provisioningStatus", doc("provisioningStatus", 1), false, false),
            IndexSpec.standard(
                "idx_servers_registration_cleanup",
                doc("emailVerified", 1).append("provisioningStatus", 1).append("createdAt", 1).append("emailVerificationToken", 1),
                false,
                true
            ),
            IndexSpec.standard("uidx_servers_provisioningSignInToken", doc("provisioningSignInToken", 1), true, true),
            IndexSpec.standard("uidx_servers_stripeCustomerId", doc("stripeCustomerId", 1), true, true),
            IndexSpec.standard("uidx_servers_stripeSubscriptionId", doc("stripeSubscriptionId", 1), true, true),
            IndexSpec.standard("uidx_servers_customDomainOverride", doc("customDomainOverride", 1), true, true),
            IndexSpec.standard("uidx_servers_customDomainCloudflareId", doc("customDomainCloudflareId", 1), true, true),
            IndexSpec.standard("uidx_servers_cliSetupToken", doc("cliSetupToken", 1), true, true),
            IndexSpec.standard("uidx_servers_apiKey", doc("apiKey", 1), true, true),
            IndexSpec.standard("idx_servers_userCount", doc("userCount", 1), false, false),
            IndexSpec.standard("idx_servers_ticketCount", doc("ticketCount", 1), false, false),
            IndexSpec.standard("idx_servers_lastStatsUpdatedAt", doc("lastStatsUpdatedAt", 1), false, false),
            IndexSpec.standard("idx_servers_createdAt", doc("createdAt", 1), false, false),
            IndexSpec.standard("idx_servers_lastActivityAt", doc("lastActivityAt", -1), false, true)
        ));

        ensureIndexes(template, CollectionName.METRIC_SNAPSHOTS, List.of(
            IndexSpec.standard("idx_metric_snapshots_date", doc("date", -1), false, false)
        ));

        ensureIndexes(template, CollectionName.REPLAY_LITE_REPLAYS, List.of(
            IndexSpec.standard("uidx_replay_lite_objectKey", doc("objectKey", 1), true, false),
            IndexSpec.standard("idx_replay_lite_expiresAt", doc("expiresAt", 1), false, true),
            IndexSpec.standard(
                "idx_replay_lite_server_status_confirmedAt",
                doc("pluginServerUuid", 1).append("status", 1).append("confirmedAt", 1),
                false,
                false
            ),
            IndexSpec.standard(
                "idx_replay_lite_server_status_createdAt",
                doc("pluginServerUuid", 1).append("status", 1).append("createdAt", 1),
                false,
                false
            ),
            IndexSpec.standard("idx_replay_lite_status_createdAt", doc("status", 1).append("createdAt", 1), false, false)
        ));

        ensureIndexes(template, CollectionName.REPLAY_LITE_DAILY_QUOTAS, List.of(
            IndexSpec.standard(
                "uidx_replay_lite_daily_quotas_server_day",
                doc("pluginServerUuid", 1).append("day", 1),
                true,
                false
            ),
            IndexSpec.ttl("idx_replay_lite_daily_quotas_expiresAt_ttl", doc("expiresAt", 1), 0)
        ));

        ensureIndexes(template, "admin_users", List.of(
            IndexSpec.standard("uidx_admin_users_email", doc("email", 1), true, false)
        ));

        ensureIndexes(template, CollectionName.SYSTEM_ALERTS, List.of(
            IndexSpec.standard("idx_system_alerts_expiresAt", doc("expiresAt", 1), false, false),
            IndexSpec.standard("idx_system_alerts_createdAt", doc("createdAt", -1), false, false),
            IndexSpec.standard("idx_system_alerts_audience_expiresAt", doc("audience", 1).append("expiresAt", 1), false, false)
        ));
    }

    public void createTenantIndexes(MongoTemplate template) {
        ensureIndexes(template, CollectionName.SETTINGS, List.of(
            IndexSpec.standard("uidx_settings_type", doc("type", 1), true, false)
        ));

        ensureIndexes(template, CollectionName.PLAYERS, List.of(
            IndexSpec.standard("uidx_players_minecraftUuid", doc("minecraftUuid", 1), true, true),
            IndexSpec.standard("idx_players_punishments_issued_desc", doc("punishments.issued", -1), false, false),
            IndexSpec.standard(
                "idx_players_punishments_issuerName_issued_desc",
                doc("punishments.issuerName", 1).append("punishments.issued", -1),
                false,
                false
            ),
            IndexSpec.standard(
                "idx_players_punishments_issuerId_issued_desc",
                doc("punishments.issuerId", 1).append("punishments.issued", -1),
                false,
                true
            ),
            IndexSpec.standard("idx_players_ipAddresses_ipAddress", doc("ipAddresses.ipAddress", 1), false, false),
            IndexSpec.standard("idx_players_usernames_username", doc("usernames.username", 1), false, false),
            IndexSpec.standard("idx_players_punishments_id", doc("punishments.id", 1), false, true),
            IndexSpec.standard("idx_players_data_isOnline", doc("data.isOnline", 1), false, true),
            IndexSpec.standard("idx_players_ipAddresses_firstLogin", doc("ipAddresses.firstLogin", -1), false, false)
        ));

        ensureIndexes(template, CollectionName.STAFF, List.of(
            IndexSpec.standard("uidx_staff_email", doc("email", 1), true, false),
            IndexSpec.standard("uidx_staff_username", doc("username", 1), true, false),
            IndexSpec.standard("sidx_staff_assignedMinecraftUuid", doc("assignedMinecraftUuid", 1), false, true)
        ));

        ensureIndexes(template, CollectionName.STAFF_ROLES, List.of(
            IndexSpec.standard("uidx_staff_roles_name", doc("name", 1), true, false),
            IndexSpec.standard("idx_staff_roles_order", doc("order", 1), false, false)
        ));

        ensureIndexes(template, CollectionName.INVITATIONS, List.of(
            IndexSpec.standard("idx_invitations_email", doc("email", 1), false, false),
            IndexSpec.standard("uidx_invitations_token", doc("token", 1), true, false),
            IndexSpec.ttl("idx_invitations_expiresAt_ttl", doc("expiresAt", 1), 0)
        ));

        ensureIndexes(template, CollectionName.TICKET_VERIFICATIONS, List.of(
            IndexSpec.ttl("idx_ticket_verifications_expiresAt_ttl", doc("expiresAt", 1), 0)
        ));

        ensureIndexes(template, CollectionName.TICKETS, List.of(
            IndexSpec.standard("idx_tickets_status_created", doc("status", 1).append("created", -1), false, false),
            IndexSpec.standard("idx_tickets_created", doc("created", -1), false, false),
            IndexSpec.standard("idx_tickets_updatedAt", doc("updatedAt", -1), false, false),
            IndexSpec.standard("idx_tickets_type_created", doc("type", 1).append("created", -1), false, false),
            IndexSpec.standard("idx_tickets_creatorUuid_created", doc("creatorUuid", 1).append("created", -1), false, false),
            IndexSpec.standard("idx_tickets_reportedPlayerUuid_created", doc("reportedPlayerUuid", 1).append("created", -1), false, false),
            IndexSpec.standard("idx_tickets_locked_created", doc("locked", 1).append("created", -1), false, false),
            IndexSpec.standard("idx_tickets_assignedTo_updatedAt", doc("assignedTo", 1).append("updatedAt", -1), false, false),
            IndexSpec.standard("idx_tickets_creatorName_created", doc("creatorName", 1).append("created", -1), false, false),
            IndexSpec.standard("idx_tickets_replies_name_created", doc("replies.name", 1).append("replies.created", -1), false, false),
            IndexSpec.standard("idx_tickets_tags", doc("tags", 1), false, false),
            IndexSpec.standard("idx_tickets_replayUrl", doc("replayUrl", 1), false, true)
        ));

        ensureIndexes(template, CollectionName.REPLAYS, List.of(
            IndexSpec.standard("idx_replays_targetUuid_createdAt", doc("targetUuid", 1).append("createdAt", -1), false, true),
            IndexSpec.standard("idx_replays_status_createdAt", doc("status", 1).append("createdAt", 1), false, false)
        ));

        ensureIndexes(template, CollectionName.KNOWLEDGEBASE_CATEGORIES, List.of(
            IndexSpec.standard("uidx_knowledgebase_categories_slug", doc("slug", 1), true, false),
            IndexSpec.standard("idx_knowledgebase_categories_name", doc("name", 1), false, false),
            IndexSpec.standard("idx_knowledgebase_categories_ordinal", doc("ordinal", 1), false, false),
            IndexSpec.standard("idx_knowledgebase_categories_isVisible_ordinal", doc("isVisible", 1).append("ordinal", 1), false, false)
        ));

        ensureIndexes(template, CollectionName.KNOWLEDGEBASE_ARTICLES, List.of(
            IndexSpec.standard("uidx_knowledgebase_articles_slug", doc("slug", 1), true, false),
            IndexSpec.standard("idx_knowledgebase_articles_categoryId_ordinal", doc("categoryId", 1).append("ordinal", 1), false, false),
            IndexSpec.standard("idx_knowledgebase_articles_isVisible_categoryId_ordinal", doc("isVisible", 1).append("categoryId", 1).append("ordinal", 1),
                false, false)
        ));

        ensureIndexes(template, CollectionName.WEBAUTHN_CREDENTIALS, List.of(
            IndexSpec.standard("idx_webauthn_credentials_email", doc("email", 1), false, false),
            IndexSpec.standard("uidx_webauthn_credentials_credentialId", doc("credentialId", 1), true, false),
            IndexSpec.standard("idx_webauthn_credentials_userHandle", doc("userHandle", 1), false, false)
        ));

        ensureIndexes(template, CollectionName.WEBAUTHN_CHALLENGES, List.of(
            IndexSpec.ttl("idx_webauthn_challenges_expiresAt_ttl", doc("expiresAt", 1), 0)
        ));

        ensureIndexes(template, CollectionName.HOMEPAGE_CARDS, List.of(
            IndexSpec.standard("idx_homepage_cards_ordinal", doc("ordinal", 1), false, false),
            IndexSpec.standard("idx_homepage_cards_isEnabled_ordinal", doc("isEnabled", 1).append("ordinal", 1), false, false),
            IndexSpec.standard("idx_homepage_cards_categoryId", doc("categoryId", 1), false, true)
        ));

        ensureIndexes(template, CollectionName.SESSIONS, List.of(
            IndexSpec.standard("idx_sessions_email", doc("email", 1), false, false),
            IndexSpec.ttl("idx_sessions_expiresAt_ttl", doc("expiresAt", 1), 0)
        ));

        ensureIndexes(template, CollectionName.AUTH_CODES, List.of(
            IndexSpec.ttl("idx_auth_codes_expiresAt_ttl", doc("expiresAt", 1), 0)
        ));

        ensureIndexes(template, CollectionName.SYSTEM_LOGS, List.of(
            IndexSpec.standard("idx_system_logs_timestamp", doc("timestamp", -1), false, false),
            IndexSpec.standard("idx_system_logs_level_timestamp", doc("level", 1).append("timestamp", -1), false, false),
            IndexSpec.standard("idx_system_logs_source_timestamp", doc("source", 1).append("timestamp", -1), false, false)
        ));

        ensureIndexes(template, CollectionName.SECURITY_EVENTS, List.of(
            IndexSpec.standard("idx_security_events_timestamp", doc("timestamp", -1), false, false),
            IndexSpec.standard("idx_security_events_severity_timestamp", doc("severity", 1).append("timestamp", -1), false, false)
        ));

        ensureIndexes(template, CollectionName.CHAT_LOGS, List.of(
            IndexSpec.standard("idx_chat_logs_uuid_timestamp", doc("uuid", 1).append("timestamp", -1), false, false)
        ));

        ensureIndexes(template, CollectionName.COMMAND_LOGS, List.of(
            IndexSpec.standard("idx_command_logs_uuid_timestamp", doc("uuid", 1).append("timestamp", -1), false, false)
        ));

        ensureIndexes(template, CollectionName.LOGS, List.of(
            IndexSpec.standard("idx_logs_created_desc", doc("created", -1), false, false)
        ));

        ensureIndexes(template, CollectionName.MIGRATIONS, List.of(
            IndexSpec.standard("idx_migrations_status_startedAt", doc("status", 1).append("startedAt", -1), false, false)
        ));
    }

    private static void ensureIndexes(MongoTemplate template, String collectionName, List<IndexSpec> specs) {
        IndexOperations indexOps = template.indexOps(collectionName);
        List<IndexInfo> existingIndexes = indexOps.getIndexInfo();
        for (IndexSpec spec : specs) {
            if (hasEquivalentIndex(existingIndexes, spec)) {
                continue;
            }
            createIndex(indexOps, spec);
        }
    }

    private static void createIndex(IndexOperations indexOps, IndexSpec spec) {
        Index index = new Index().named(spec.name());

        for (Map.Entry<String, Object> entry : spec.keys().entrySet()) {
            Sort.Direction direction = directionFrom(entry.getValue());
            index.on(entry.getKey(), direction);
        }

        if (spec.unique()) {
            index.unique();
        }
        if (spec.sparse()) {
            index.sparse();
        }
        if (spec.ttlSeconds() != null) {
            index.expire(Duration.ofSeconds(spec.ttlSeconds()));
        }

        indexOps.createIndex(index);
    }

    private static Sort.Direction directionFrom(Object value) {
        if (value instanceof Number number) {
            return number.intValue() < 0 ? Sort.Direction.DESC : Sort.Direction.ASC;
        }
        throw new ValidationException("Unsupported index direction value: " + value);
    }

    private static boolean hasEquivalentIndex(List<IndexInfo> existingIndexes, IndexSpec spec) {
        List<IndexField> expectedFields = fieldsFor(spec.keys());
        for (IndexInfo existingIndex : existingIndexes) {
            if (!existingIndex.getIndexFields().equals(expectedFields)) {
                continue;
            }
            if (existingIndex.isUnique() != spec.unique()) {
                continue;
            }
            if (existingIndex.isSparse() != spec.sparse()) {
                continue;
            }

            long existingTtlSeconds = existingIndex.getExpireAfter().map(Duration::getSeconds).orElse(-1L);
            long expectedTtlSeconds = spec.ttlSeconds() == null ? -1L : spec.ttlSeconds();
            if (existingTtlSeconds != expectedTtlSeconds) {
                continue;
            }

            return true;
        }
        return false;
    }

    private static List<IndexField> fieldsFor(Document keys) {
        List<IndexField> fields = new ArrayList<>(keys.size());
        for (Map.Entry<String, Object> entry : keys.entrySet()) {
            fields.add(IndexField.create(entry.getKey(), directionFrom(entry.getValue())));
        }
        return fields;
    }

    private static Document doc(String field, int direction) {
        return new Document(field, direction);
    }

    private record IndexSpec(
        String name,
        Document keys,
        boolean unique,
        boolean sparse,
        Long ttlSeconds
    ) {
        static IndexSpec standard(String name, Document keys, boolean unique, boolean sparse) {
            return new IndexSpec(name, keys, unique, sparse, null);
        }

        static IndexSpec ttl(String name, Document keys, long ttlSeconds) {
            return new IndexSpec(name, keys, false, false, ttlSeconds);
        }
    }
}
