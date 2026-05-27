package gg.modl.backend.database.mongo.repository;

import com.mongodb.client.result.UpdateResult;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.AbstractGlobalMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.ServerFields;
import gg.modl.backend.server.data.CustomDomainStatus;
import gg.modl.backend.server.data.ProvisioningStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.data.SubscriptionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.aggregation.ProjectionOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class ServerMongoRepository extends AbstractGlobalMongoRepository<Server> {
    private static final String FILTER_ALL = "all";
    private static final String FILTER_ACTIVE = "active";
    private static final String FILTER_PENDING = "pending";
    private static final String FILTER_FAILED = "failed";
    private static final String FILTER_UNVERIFIED = "unverified";
    private static final String DOMAIN_STATUS_ACTIVE = "ACTIVE";

    private static final String ALIAS_TOTAL = "total";
    private static final String ALIAS_TOTAL_USERS = "totalUsers";
    private static final String ALIAS_TOTAL_TICKETS = "totalTickets";
    private static final String ALIAS_VALUE = "value";
    private static final String ALIAS_NAME = "name";
    private static final String ALIAS_DATE = "date";
    private static final String ALIAS_SERVERS = "servers";
    private static final String ALIAS_VALUE_SOURCE = "valueSource";

    private static final String METRIC_USERS = "users";
    private static final String METRIC_TICKETS = "tickets";

    private static final String ORDER_ASC = "asc";
    private static final String INVALID_PLAN_SENTINEL = "__invalid_plan__";
    private static final String RESET_MESSAGE = "Database reset - awaiting reprovisioning";

    private static final Set<String> ADMIN_SORT_FIELDS = Set.of(
        ServerFields.SERVER_NAME,
        ServerFields.CUSTOM_DOMAIN,
        ServerFields.ADMIN_EMAIL,
        ServerFields.PLAN,
        ServerFields.CREATED_AT,
        ServerFields.UPDATED_AT,
        ServerFields.USER_COUNT,
        ServerFields.PROVISIONING_STATUS,
        ServerFields.LAST_ACTIVITY_AT
    );

    public ServerMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(Server.class, CollectionName.MODL_SERVERS, tenantMongoAccess);
    }

    public long countAll() {
        return count(new Query());
    }

    public Optional<Server> findByCustomDomain(String customDomain) {
        return findOne(Query.query(Criteria.where(ServerFields.CUSTOM_DOMAIN).is(customDomain)));
    }

    public Optional<Server> findByActiveCustomDomainOverride(String domain) {
        Criteria criteria = new Criteria().andOperator(
            Criteria.where(ServerFields.CUSTOM_DOMAIN_OVERRIDE).is(domain),
            Criteria.where(ServerFields.CUSTOM_DOMAIN_STATUS).is(DOMAIN_STATUS_ACTIVE)
        );
        return findOne(new Query(criteria));
    }

    public Optional<Server> findMatchingIdentity(String email, String serverName, String subdomain) {
        Criteria criteria = new Criteria().orOperator(
            Criteria.where(ServerFields.ADMIN_EMAIL).is(email),
            Criteria.where(ServerFields.SERVER_NAME).is(serverName),
            Criteria.where(ServerFields.CUSTOM_DOMAIN).is(subdomain)
        );
        return findOne(new Query(criteria));
    }

    public Optional<Server> findByDatabaseName(String databaseName) {
        return findOne(Query.query(Criteria.where(ServerFields.DATABASE_NAME).is(databaseName)));
    }

    public Optional<Server> findByApiKey(String apiKey) {
        return findOne(Query.query(Criteria.where(ServerFields.API_KEY).is(apiKey)));
    }

    public boolean existsByAdminEmailExcludingId(String adminEmail, String excludedServerId) {
        Criteria criteria = Criteria.where(ServerFields.ADMIN_EMAIL)
            .regex("^" + Pattern.quote(adminEmail) + "$", "i")
            .and(ServerFields.ID).ne(excludedServerId);
        return exists(Query.query(criteria));
    }

    public Optional<Server> findByEmailVerificationToken(String token) {
        return findOne(Query.query(Criteria.where(ServerFields.EMAIL_VERIFICATION_TOKEN).is(token)));
    }

    public Optional<Server> verifyEmailTokenAtomically(String token) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.EMAIL_VERIFICATION_TOKEN).is(token),
            Criteria.where(ServerFields.EMAIL_VERIFIED).is(false),
            Criteria.where(ServerFields.PROVISIONING_STATUS).is(ProvisioningStatus.PENDING),
            noCleanupClaimCriteria()
        ));

        Update update = new Update()
            .set(ServerFields.EMAIL_VERIFIED, true)
            .unset(ServerFields.EMAIL_VERIFICATION_TOKEN)
            .set(ServerFields.PROVISIONING_STATUS, ProvisioningStatus.COMPLETED)
            .set(ServerFields.UPDATED_AT, new Date());

        return Optional.ofNullable(findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true)
        ));
    }

    public Optional<Server> findByProvisioningSignInToken(String token) {
        return findOne(Query.query(Criteria.where(ServerFields.PROVISIONING_SIGN_IN_TOKEN).is(token)));
    }

    public Optional<Server> consumeProvisioningSignInToken(String token, Date now) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.PROVISIONING_SIGN_IN_TOKEN).is(token),
            Criteria.where(ServerFields.PROVISIONING_SIGN_IN_TOKEN_EXPIRES_AT).gt(now),
            Criteria.where(ServerFields.EMAIL_VERIFIED).is(true),
            Criteria.where(ServerFields.PROVISIONING_STATUS).is(ProvisioningStatus.COMPLETED)
        ));
        Update update = new Update()
            .unset(ServerFields.PROVISIONING_SIGN_IN_TOKEN)
            .unset(ServerFields.PROVISIONING_SIGN_IN_TOKEN_EXPIRES_AT)
            .set(ServerFields.UPDATED_AT, now);
        return Optional.ofNullable(findAndModify(query, update, FindAndModifyOptions.options().returnNew(true)));
    }

    public Optional<Server> findByCliSetupToken(String token) {
        return findOne(Query.query(Criteria.where(ServerFields.CLI_SETUP_TOKEN).is(token)));
    }

    public Optional<Server> findByStripeCustomerId(String customerId) {
        return findOne(Query.query(Criteria.where(ServerFields.STRIPE_CUSTOMER_ID).is(customerId)));
    }

    public Optional<Server> findByStripeSubscriptionId(String subscriptionId) {
        return findOne(Query.query(Criteria.where(ServerFields.STRIPE_SUBSCRIPTION_ID).is(subscriptionId)));
    }

    public long countCompletedAndVerified() {
        return count(Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.PROVISIONING_STATUS).is(ProvisioningStatus.COMPLETED),
            Criteria.where(ServerFields.EMAIL_VERIFIED).is(true)
        )));
    }

    public long countByProvisioningStatus(ProvisioningStatus status) {
        return count(Query.query(Criteria.where(ServerFields.PROVISIONING_STATUS).is(status)));
    }

    public long countByProvisioningStatuses(ProvisioningStatus... statuses) {
        return count(Query.query(Criteria.where(ServerFields.PROVISIONING_STATUS).in((Object[]) statuses)));
    }

    public long countActiveSince(Date activityCutoff) {
        return count(Query.query(Criteria.where(ServerFields.LAST_ACTIVITY_AT).gte(activityCutoff)));
    }

    public long countCompletedWithUsers() {
        return count(Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.PROVISIONING_STATUS).is(ProvisioningStatus.COMPLETED),
            Criteria.where(ServerFields.USER_COUNT).gt(0)
        )));
    }

    public long countCreatedSince(Date startDate) {
        return count(Query.query(Criteria.where(ServerFields.CREATED_AT).gte(startDate)));
    }

    public long countCreatedBetween(Date startDate, Date endDate) {
        return count(Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.CREATED_AT).gte(startDate),
            Criteria.where(ServerFields.CREATED_AT).lt(endDate)
        )));
    }

    public long sumOnlinePlayersSince(Date activityCutoff) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where(ServerFields.LAST_ACTIVITY_AT).gte(activityCutoff)),
            Aggregation.group().sum(ServerFields.ONLINE_PLAYER_COUNT).as(ALIAS_TOTAL)
        );
        Document result = aggregate(aggregation, Document.class).getUniqueMappedResult();
        return extractLong(result, ALIAS_TOTAL);
    }

    private long extractLong(Document document, String fieldName) {
        if (document == null) {
            return 0L;
        }

        Object value = document.get(fieldName);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public UsageTotals getUsageTotals() {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.group()
                .sum(ServerFields.USER_COUNT).as(ALIAS_TOTAL_USERS)
                .sum(ServerFields.TICKET_COUNT).as(ALIAS_TOTAL_TICKETS)
        );
        Document result = aggregate(aggregation, Document.class).getUniqueMappedResult();
        return new UsageTotals(
            extractLong(result, ALIAS_TOTAL_USERS),
            extractLong(result, ALIAS_TOTAL_TICKETS)
        );
    }

    public List<NameValueResult> aggregatePlanCounts() {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.group(ServerFields.PLAN).count().as(ALIAS_VALUE),
            Aggregation.project().and("_id").as(ALIAS_NAME).and(ALIAS_VALUE).as(ALIAS_VALUE)
        );
        return aggregate(aggregation, NameValueResult.class).getMappedResults();
    }

    public List<NameValueResult> aggregateProvisioningStatusCounts() {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.group(ServerFields.PROVISIONING_STATUS).count().as(ALIAS_VALUE),
            Aggregation.project().and("_id").as(ALIAS_NAME).and(ALIAS_VALUE).as(ALIAS_VALUE)
        );
        return aggregate(aggregation, NameValueResult.class).getMappedResults();
    }

    public List<DateServersResult> findRegistrationTrend(Date startDate) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where(ServerFields.CREATED_AT).gte(startDate)),
            Aggregation.project()
                .and(DateOperators.DateToString.dateOf(ServerFields.CREATED_AT).toString("%Y-%m-%d")).as(ALIAS_DATE),
            Aggregation.group(ALIAS_DATE).count().as(ALIAS_SERVERS),
            Aggregation.sort(Sort.Direction.ASC, "_id"),
            Aggregation.project().and("_id").as(ALIAS_DATE).and(ALIAS_SERVERS).as(ALIAS_SERVERS)
        );
        return aggregate(aggregation, DateServersResult.class).getMappedResults();
    }

    public List<Server> findTopCompletedVerifiedByUserCount(int limit) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.PROVISIONING_STATUS).is(ProvisioningStatus.COMPLETED),
            Criteria.where(ServerFields.EMAIL_VERIFIED).is(true),
            Criteria.where(ServerFields.USER_COUNT).gt(0)
        ));
        query.with(Sort.by(Sort.Direction.DESC, ServerFields.USER_COUNT));
        query.limit(limit);
        return find(query);
    }

    public List<Server> findAdminServers(String search, String plan, String status, String sortField, String sortOrder, int skip, int limit) {
        Query query = buildAdminServerFilterQuery(search, plan, status);
        query.with(Sort.by(resolveSortDirection(sortOrder), resolveAdminSortField(sortField)));
        query.skip(skip).limit(limit);
        query.fields()
            .include(ServerFields.SERVER_NAME)
            .include(ServerFields.CUSTOM_DOMAIN)
            .include(ServerFields.ADMIN_EMAIL)
            .include(ServerFields.PLAN)
            .include(ServerFields.EMAIL_VERIFIED)
            .include(ServerFields.PROVISIONING_STATUS)
            .include(ServerFields.CREATED_AT)
            .include(ServerFields.UPDATED_AT)
            .include(ServerFields.USER_COUNT)
            .include(ServerFields.TICKET_COUNT)
            .include(ServerFields.LAST_ACTIVITY_AT);
        return find(query);
    }

    private Query buildAdminServerFilterQuery(String search, String plan, String status) {
        Query query = new Query();
        List<Criteria> criteriaList = new ArrayList<>();

        if (search != null && !search.trim().isEmpty()) {
            String escapedSearch = Pattern.quote(search.trim());
            criteriaList.add(new Criteria().orOperator(
                Criteria.where(ServerFields.SERVER_NAME).regex(escapedSearch, "i"),
                Criteria.where(ServerFields.CUSTOM_DOMAIN).regex(escapedSearch, "i"),
                Criteria.where(ServerFields.ADMIN_EMAIL).regex(escapedSearch, "i")
            ));
        }

        if (plan != null && !FILTER_ALL.equals(plan)) {
            try {
                criteriaList.add(Criteria.where(ServerFields.PLAN).is(ServerPlan.valueOf(plan.trim().toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException ignored) {
                criteriaList.add(Criteria.where(ServerFields.PLAN).is(INVALID_PLAN_SENTINEL));
            }
        }

        if (status != null && !FILTER_ALL.equals(status)) {
            switch (status) {
                case FILTER_ACTIVE -> {
                    criteriaList.add(Criteria.where(ServerFields.PROVISIONING_STATUS).is(ProvisioningStatus.COMPLETED));
                    criteriaList.add(Criteria.where(ServerFields.EMAIL_VERIFIED).is(true));
                }
                case FILTER_PENDING -> criteriaList.add(Criteria.where(ServerFields.PROVISIONING_STATUS)
                    .in(ProvisioningStatus.PENDING, ProvisioningStatus.IN_PROGRESS));
                case FILTER_FAILED -> criteriaList.add(Criteria.where(ServerFields.PROVISIONING_STATUS).is(ProvisioningStatus.FAILED));
                case FILTER_UNVERIFIED -> criteriaList.add(Criteria.where(ServerFields.EMAIL_VERIFIED).is(false));
                default -> {
                }
            }
        }

        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }
        return query;
    }

    private Sort.Direction resolveSortDirection(String sortOrder) {
        return ORDER_ASC.equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
    }

    private String resolveAdminSortField(String sortField) {
        return ADMIN_SORT_FIELDS.contains(sortField) ? sortField : ServerFields.CREATED_AT;
    }

    public long countAdminServers(String search, String plan, String status) {
        return count(buildAdminServerFilterQuery(search, plan, status));
    }

    public List<Server> findUsageRefreshCandidates(Date staleCutoff, int limit) {
        Criteria staleCriteria = new Criteria().orOperator(
            Criteria.where(ServerFields.LAST_STATS_UPDATED_AT).exists(false),
            Criteria.where(ServerFields.LAST_STATS_UPDATED_AT).lt(staleCutoff)
        );

        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.DATABASE_NAME).ne(null),
            Criteria.where(ServerFields.PROVISIONING_STATUS).is(ProvisioningStatus.COMPLETED),
            Criteria.where(ServerFields.EMAIL_VERIFIED).is(true),
            staleCriteria
        ));
        query.with(Sort.by(Sort.Direction.ASC, ServerFields.LAST_STATS_UPDATED_AT));
        query.limit(limit);
        query.fields()
            .include(ServerFields.SERVER_NAME)
            .include(ServerFields.CUSTOM_DOMAIN)
            .include(ServerFields.DATABASE_NAME)
            .include(ServerFields.ADMIN_EMAIL)
            .include(ServerFields.EMAIL_VERIFIED)
            .include(ServerFields.PLAN)
            .include(ServerFields.USER_COUNT)
            .include(ServerFields.TICKET_COUNT)
            .include(ServerFields.LAST_STATS_UPDATED_AT)
            .include(ServerFields.LAST_ACTIVITY_AT)
            .include(ServerFields.UPDATED_AT);
        return find(query);
    }

    public List<Server> findExpiredRegistrationCleanupCandidates(Date cutoff, int limit) {
        return findExpiredRegistrationCleanupCandidates(cutoff, new Date(0), limit);
    }

    public List<Server> findExpiredRegistrationCleanupCandidates(Date cutoff, Date claimCutoff, int limit) {
        Query query = Query.query(expiredRegistrationCriteria(cutoff, claimCutoff));
        query.with(Sort.by(Sort.Direction.ASC, ServerFields.CREATED_AT));
        query.limit(limit);
        query.fields()
            .include(ServerFields.SERVER_NAME)
            .include(ServerFields.CUSTOM_DOMAIN)
            .include(ServerFields.DATABASE_NAME)
            .include(ServerFields.ADMIN_EMAIL)
            .include(ServerFields.EMAIL_VERIFIED)
            .include(ServerFields.EMAIL_VERIFICATION_TOKEN)
            .include(ServerFields.PROVISIONING_STATUS)
            .include(ServerFields.API_KEY)
            .include(ServerFields.ONLINE_PLAYER_COUNT)
            .include(ServerFields.USER_COUNT)
            .include(ServerFields.TICKET_COUNT)
            .include(ServerFields.LAST_ACTIVITY_AT)
            .include(ServerFields.CREATED_AT)
            .include(ServerFields.UPDATED_AT)
            .include(ServerFields.CLEANUP_CLAIM_ID)
            .include(ServerFields.CLEANUP_CLAIMED_AT);
        return find(query);
    }

    public Optional<Server> claimExpiredRegistrationForCleanup(String serverId, Date cutoff, Instant claimedAt) {
        return claimExpiredRegistrationForCleanup(serverId, cutoff, new Date(0), claimedAt);
    }

    public Optional<Server> claimExpiredRegistrationForCleanup(String serverId, Date cutoff, Date claimCutoff, Instant claimedAt) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.ID).is(serverId),
            expiredRegistrationCriteria(cutoff, claimCutoff)
        ));
        Update update = new Update()
            .set(ServerFields.CLEANUP_CLAIM_ID, UUID.randomUUID().toString())
            .set(ServerFields.CLEANUP_CLAIMED_AT, Date.from(claimedAt))
            .set(ServerFields.UPDATED_AT, Date.from(claimedAt));

        return Optional.ofNullable(findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true)
        ));
    }

    public boolean deleteClaimedExpiredRegistration(String serverId, String cleanupClaimId, Date cutoff) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.ID).is(serverId),
            Criteria.where(ServerFields.CLEANUP_CLAIM_ID).is(cleanupClaimId),
            explicitExpiredRegistrationCriteria(cutoff)
        ));
        return remove(query).getDeletedCount() > 0;
    }

    public Optional<Server> confirmRegistrationCleanupClaim(String serverId, String cleanupClaimId, Date cutoff, Instant confirmedAt) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.ID).is(serverId),
            Criteria.where(ServerFields.CLEANUP_CLAIM_ID).is(cleanupClaimId),
            explicitExpiredRegistrationCriteria(cutoff)
        ));
        Update update = new Update()
            .set(ServerFields.CLEANUP_CLAIMED_AT, Date.from(confirmedAt))
            .set(ServerFields.UPDATED_AT, Date.from(confirmedAt));

        return Optional.ofNullable(findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true)
        ));
    }

    public boolean releaseRegistrationCleanupClaim(String serverId, String cleanupClaimId) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.ID).is(serverId),
            Criteria.where(ServerFields.CLEANUP_CLAIM_ID).is(cleanupClaimId)
        ));
        Update update = new Update()
            .unset(ServerFields.CLEANUP_CLAIM_ID)
            .unset(ServerFields.CLEANUP_CLAIMED_AT)
            .set(ServerFields.UPDATED_AT, new Date());
        return updateFirst(query, update).getModifiedCount() > 0;
    }

    public List<Server> findUsageTargetsByIds(List<String> serverIds) {
        Query query = Query.query(Criteria.where(ServerFields.ID).in(serverIds));
        query.fields()
            .include(ServerFields.SERVER_NAME)
            .include(ServerFields.CUSTOM_DOMAIN)
            .include(ServerFields.DATABASE_NAME)
            .include(ServerFields.ADMIN_EMAIL)
            .include(ServerFields.EMAIL_VERIFIED)
            .include(ServerFields.PLAN)
            .include(ServerFields.USER_COUNT)
            .include(ServerFields.TICKET_COUNT)
            .include(ServerFields.LAST_STATS_UPDATED_AT)
            .include(ServerFields.LAST_ACTIVITY_AT)
            .include(ServerFields.UPDATED_AT);
        return find(query);
    }

    private Criteria expiredRegistrationCriteria(Date cutoff, Date claimCutoff) {
        return new Criteria().andOperator(
            explicitExpiredRegistrationCriteria(cutoff),
            cleanupClaimEligibleCriteria(claimCutoff)
        );
    }

    private Criteria explicitExpiredRegistrationCriteria(Date cutoff) {
        return new Criteria().andOperator(
            Criteria.where(ServerFields.EMAIL_VERIFIED).is(false),
            Criteria.where(ServerFields.PROVISIONING_STATUS).is(ProvisioningStatus.PENDING),
            Criteria.where(ServerFields.EMAIL_VERIFICATION_TOKEN).exists(true).nin(null, ""),
            Criteria.where(ServerFields.CREATED_AT).exists(true).lt(cutoff),
            Criteria.where(ServerFields.DATABASE_NAME).regex("^server_.+"),
            notPresentOrBlank(ServerFields.API_KEY),
            notPresent(ServerFields.LAST_ACTIVITY_AT),
            notPositive(ServerFields.USER_COUNT),
            notPositive(ServerFields.TICKET_COUNT),
            notPositive(ServerFields.ONLINE_PLAYER_COUNT)
        );
    }

    private Criteria cleanupClaimEligibleCriteria(Date claimCutoff) {
        return new Criteria().orOperator(
            Criteria.where(ServerFields.CLEANUP_CLAIM_ID).exists(false),
            Criteria.where(ServerFields.CLEANUP_CLAIM_ID).is(null),
            Criteria.where(ServerFields.CLEANUP_CLAIMED_AT).lt(claimCutoff)
        );
    }

    private Criteria noCleanupClaimCriteria() {
        return new Criteria().orOperator(
            Criteria.where(ServerFields.CLEANUP_CLAIM_ID).exists(false),
            Criteria.where(ServerFields.CLEANUP_CLAIM_ID).is(null)
        );
    }

    private Criteria notPresent(String field) {
        return new Criteria().orOperator(
            Criteria.where(field).exists(false),
            Criteria.where(field).is(null)
        );
    }

    private Criteria notPresentOrBlank(String field) {
        return new Criteria().orOperator(
            Criteria.where(field).exists(false),
            Criteria.where(field).is(null),
            Criteria.where(field).is("")
        );
    }

    private Criteria notPositive(String field) {
        return new Criteria().orOperator(
            Criteria.where(field).exists(false),
            Criteria.where(field).is(null),
            Criteria.where(field).lte(0)
        );
    }

    public List<Server> findProvisioningCandidatesByIds(List<String> serverIds) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.ID).in(serverIds),
            Criteria.where(ServerFields.DATABASE_NAME).exists(true).ne(null)
        ));
        return find(query);
    }

    public List<Server> findCancelledWithPeriodEnd() {
        Criteria criteria = new Criteria().andOperator(
            Criteria.where(ServerFields.SUBSCRIPTION_STATUS).is(SubscriptionStatus.CANCELED),
            Criteria.where(ServerFields.CURRENT_PERIOD_END).exists(true).ne(null)
        );
        return find(new Query(criteria));
    }

    public void incrementCdnUsage(String serverId, double additionalGb) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update().inc(ServerFields.CDN_USAGE_CURRENT_PERIOD, additionalGb)
        );
    }

    public void incrementAiRequests(String serverId, long additionalRequests) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update().inc(ServerFields.AI_REQUESTS_CURRENT_PERIOD, additionalRequests)
        );
    }

    public void incrementStorageUsed(String serverId, long bytes) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update().inc(ServerFields.STORAGE_USED_BYTES, bytes)
        );
    }

    public boolean tryIncrementStorageUsedWithinLimit(String serverId, long bytes, long maxBytes) {
        long maxCurrentBytes = maxBytes - bytes;
        if (bytes < 0 || maxCurrentBytes < 0) {
            return false;
        }
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where(ServerFields.ID).is(serverId),
            Criteria.where(ServerFields.STORAGE_USED_BYTES).lte(maxCurrentBytes)
        ));
        UpdateResult result = updateFirst(query, new Update().inc(ServerFields.STORAGE_USED_BYTES, bytes));
        return result.getModifiedCount() == 1;
    }

    public void decrementStorageUsed(String serverId, long bytes) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update().inc(ServerFields.STORAGE_USED_BYTES, -bytes)
        );
    }

    public void setStorageUsed(String serverId, long bytes) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update().set(ServerFields.STORAGE_USED_BYTES, bytes)
        );
    }

    public Optional<AIUsageSnapshot> findAIUsageSnapshotById(String serverId) {
        Query query = Query.query(Criteria.where(ServerFields.ID).is(serverId));
        query.fields()
            .include(ServerFields.AI_REQUESTS_CURRENT_PERIOD)
            .include(ServerFields.MAX_AI_OVERAGE_REQUESTS);

        Document document = globalTemplate().findOne(query, Document.class, collectionName());
        if (document == null) {
            return Optional.empty();
        }

        return Optional.of(new AIUsageSnapshot(
            extractLong(document, ServerFields.AI_REQUESTS_CURRENT_PERIOD),
            extractLong(document, ServerFields.MAX_AI_OVERAGE_REQUESTS)
        ));
    }

    public void resetUsageCounters(String serverId) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update()
                .set(ServerFields.CDN_USAGE_CURRENT_PERIOD, 0.0)
                .set(ServerFields.AI_REQUESTS_CURRENT_PERIOD, 0L)
                .unset(ServerFields.USAGE_RESET_AT)
        );
    }

    public void updateAdminEmail(String serverId, String adminEmail) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update()
                .set(ServerFields.ADMIN_EMAIL, adminEmail)
                .set(ServerFields.UPDATED_AT, new Date())
        );
    }

    public void updateApiKey(String serverId, String apiKey) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update().set(ServerFields.API_KEY, apiKey)
        );
    }

    public void clearApiKey(String serverId) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update().unset(ServerFields.API_KEY)
        );
    }

    public Optional<Server> updateAllowedFields(String serverId, Map<String, Object> updateData) {
        Update update = new Update();
        boolean hasChanges = false;

        for (Map.Entry<String, Object> entry : updateData.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }

            switch (key) {
                case ServerFields.ADMIN_EMAIL -> {
                    update.set(ServerFields.ADMIN_EMAIL, value);
                    hasChanges = true;
                }
                case ServerFields.EMAIL_VERIFIED -> {
                    update.set(ServerFields.EMAIL_VERIFIED, value);
                    hasChanges = true;
                }
                case ServerFields.PROVISIONING_STATUS -> {
                    update.set(ServerFields.PROVISIONING_STATUS, normalizeProvisioningStatus(value));
                    hasChanges = true;
                }
                case ServerFields.PROVISIONING_NOTES -> {
                    update.set(ServerFields.PROVISIONING_NOTES, value);
                    hasChanges = true;
                }
                case ServerFields.PLAN -> {
                    update.set(ServerFields.PLAN, normalizePlan(value));
                    hasChanges = true;
                }
                case ServerFields.SUBSCRIPTION_STATUS -> {
                    update.set(ServerFields.SUBSCRIPTION_STATUS, normalizeSubscriptionStatus(value));
                    hasChanges = true;
                }
                case ServerFields.LAST_ACTIVITY_AT -> {
                    update.set(ServerFields.LAST_ACTIVITY_AT, value);
                    hasChanges = true;
                }
                case ServerFields.UPDATED_AT -> {
                    update.set(ServerFields.UPDATED_AT, value);
                    hasChanges = true;
                }
                default -> {
                }
            }
        }

        if (!hasChanges) {
            return findById(serverId);
        }

        Server updated = findAndModify(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            update,
            FindAndModifyOptions.options().returnNew(true)
        );
        return Optional.ofNullable(updated);
    }

    private ServerPlan normalizePlan(Object value) {
        if (value instanceof ServerPlan plan) {
            return plan;
        }
        return ServerPlan.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
    }

    private ProvisioningStatus normalizeProvisioningStatus(Object value) {
        if (value instanceof ProvisioningStatus provisioningStatus) {
            return provisioningStatus;
        }
        return ProvisioningStatus.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
    }

    private SubscriptionStatus normalizeSubscriptionStatus(Object value) {
        if (value instanceof SubscriptionStatus subscriptionStatus) {
            return subscriptionStatus;
        }
        return SubscriptionStatus.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
    }

    public boolean deleteByServerId(String serverId) {
        return remove(Query.query(Criteria.where(ServerFields.ID).is(serverId))).getDeletedCount() > 0;
    }

    public long deleteByServerIds(List<String> serverIds) {
        return remove(Query.query(Criteria.where(ServerFields.ID).in(serverIds))).getDeletedCount();
    }

    public long bulkSuspend(List<String> serverIds, Date updatedAt) {
        Update update = new Update()
            .set(ServerFields.PROVISIONING_STATUS, ProvisioningStatus.FAILED)
            .set(ServerFields.UPDATED_AT, updatedAt);
        return updateMulti(Query.query(Criteria.where(ServerFields.ID).in(serverIds)), update).getModifiedCount();
    }

    public long bulkActivate(List<String> serverIds, Date updatedAt) {
        Update update = new Update()
            .set(ServerFields.PROVISIONING_STATUS, ProvisioningStatus.COMPLETED)
            .set(ServerFields.EMAIL_VERIFIED, true)
            .set(ServerFields.UPDATED_AT, updatedAt);
        return updateMulti(Query.query(Criteria.where(ServerFields.ID).in(serverIds)), update).getModifiedCount();
    }

    public long bulkUpdatePlan(List<String> serverIds, ServerPlan plan, Date updatedAt) {
        Update update = new Update()
            .set(ServerFields.PLAN, plan)
            .set(ServerFields.UPDATED_AT, updatedAt);
        return updateMulti(Query.query(Criteria.where(ServerFields.ID).in(serverIds)), update).getModifiedCount();
    }

    public void updateCustomDomain(String serverId, String customDomain, String status,
                                   String cloudflareHostnameId, String error) {
        CustomDomainStatus domainStatus = switch (status) {
            case "active" -> CustomDomainStatus.ACTIVE;
            case "error" -> CustomDomainStatus.ERROR;
            case "verifying" -> CustomDomainStatus.VERIFYING;
            default -> CustomDomainStatus.PENDING;
        };
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update()
                .set(ServerFields.CUSTOM_DOMAIN_OVERRIDE, customDomain)
                .set(ServerFields.CUSTOM_DOMAIN_STATUS, domainStatus.name())
                .set(ServerFields.CUSTOM_DOMAIN_CLOUDFLARE_ID, cloudflareHostnameId)
                .set(ServerFields.CUSTOM_DOMAIN_LAST_CHECKED, new Date())
                .set(ServerFields.CUSTOM_DOMAIN_ERROR, error)
                .set(ServerFields.UPDATED_AT, new Date())
        );
    }

    public void clearCustomDomain(String serverId) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update()
                .unset(ServerFields.CUSTOM_DOMAIN_OVERRIDE)
                .unset(ServerFields.CUSTOM_DOMAIN_STATUS)
                .unset(ServerFields.CUSTOM_DOMAIN_CLOUDFLARE_ID)
                .unset(ServerFields.CUSTOM_DOMAIN_LAST_CHECKED)
                .unset(ServerFields.CUSTOM_DOMAIN_ERROR)
                .set(ServerFields.UPDATED_AT, new Date())
        );
    }

    public void updateStaffPermissionsTimestamp(String serverId, Date timestamp) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update()
                .set(ServerFields.STAFF_PERMISSIONS_UPDATED_AT, timestamp)
                .set(ServerFields.UPDATED_AT, timestamp)
        );
    }

    public void updatePunishmentTypesTimestamp(String serverId, Date timestamp) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update()
                .set(ServerFields.PUNISHMENT_TYPES_UPDATED_AT, timestamp)
                .set(ServerFields.UPDATED_AT, timestamp)
        );
    }

    public void updateLastActivity(String serverId, Date lastActivityAt, long onlinePlayerCount) {
        updateFirst(
            Query.query(Criteria.where(ServerFields.ID).is(serverId)),
            new Update()
                .set(ServerFields.LAST_ACTIVITY_AT, lastActivityAt)
                .set(ServerFields.ONLINE_PLAYER_COUNT, onlinePlayerCount)
        );
    }

    public void updateUsageStats(String serverId, long userCount, long ticketCount, Date updatedAt) {
        Update update = new Update()
            .set(ServerFields.USER_COUNT, userCount)
            .set(ServerFields.TICKET_COUNT, ticketCount)
            .set(ServerFields.LAST_STATS_UPDATED_AT, updatedAt);
        updateFirst(Query.query(Criteria.where(ServerFields.ID).is(serverId)), update);
    }

    public void resetAfterDatabaseDrop(String serverId, Date updatedAt) {
        Update update = new Update()
            .set(ServerFields.PROVISIONING_STATUS, ProvisioningStatus.PENDING)
            .set(ServerFields.PROVISIONING_NOTES, RESET_MESSAGE)
            .unset(ServerFields.LAST_ACTIVITY_AT)
            .unset(ServerFields.CUSTOM_DOMAIN_STATUS)
            .unset(ServerFields.CUSTOM_DOMAIN_LAST_CHECKED)
            .unset(ServerFields.CUSTOM_DOMAIN_ERROR)
            .set(ServerFields.UPDATED_AT, updatedAt);
        updateFirst(Query.query(Criteria.where(ServerFields.ID).is(serverId)), update);
    }

    public List<DateValueResult> aggregateHistoricalMetric(String metric, Date startDate) {
        ProjectionOperation projectDateStage = Aggregation.project()
            .and(DateOperators.DateToString.dateOf(ServerFields.CREATED_AT).toString("%Y-%m-%d")).as(ALIAS_DATE);

        if (METRIC_USERS.equals(metric) || METRIC_TICKETS.equals(metric)) {
            String sumField = METRIC_USERS.equals(metric) ? ServerFields.USER_COUNT : ServerFields.TICKET_COUNT;
            Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where(ServerFields.CREATED_AT).gte(startDate)),
                projectDateStage.and(sumField).as(ALIAS_VALUE_SOURCE),
                Aggregation.group(ALIAS_DATE).sum(ALIAS_VALUE_SOURCE).as(ALIAS_VALUE),
                Aggregation.sort(Sort.Direction.ASC, "_id"),
                Aggregation.project().and("_id").as(ALIAS_DATE).and(ALIAS_VALUE).as(ALIAS_VALUE)
            );
            return aggregate(aggregation, DateValueResult.class).getMappedResults();
        }

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where(ServerFields.CREATED_AT).gte(startDate)),
            projectDateStage,
            Aggregation.group(ALIAS_DATE).count().as(ALIAS_VALUE),
            Aggregation.sort(Sort.Direction.ASC, "_id"),
            Aggregation.project().and("_id").as(ALIAS_DATE).and(ALIAS_VALUE).as(ALIAS_VALUE)
        );
        return aggregate(aggregation, DateValueResult.class).getMappedResults();
    }

    public DashboardStats aggregateDashboardStats(Date startDate, Date previousStartDate) {
        Document facet = new Document()
            .append("total", List.of(new Document("$count", "n")))
            .append("active", List.of(
                new Document("$match", new Document("provisioningStatus", ProvisioningStatus.COMPLETED.name())
                    .append("emailVerified", true)),
                new Document("$count", "n")
            ))
            .append("withUsers", List.of(
                new Document("$match", new Document("provisioningStatus", ProvisioningStatus.COMPLETED.name())
                    .append("userCount", new Document("$gt", 0))),
                new Document("$count", "n")
            ))
            .append("currentPeriod", List.of(
                new Document("$match", new Document("createdAt", new Document("$gte", startDate))),
                new Document("$count", "n")
            ))
            .append("previousPeriod", List.of(
                new Document("$match", new Document("createdAt",
                    new Document("$gte", previousStartDate).append("$lt", startDate))),
                new Document("$count", "n")
            ))
            .append("usage", List.of(
                new Document("$group", new Document("_id", null)
                    .append("totalUsers", new Document("$sum", "$userCount"))
                    .append("totalTickets", new Document("$sum", "$ticketCount")))
            ));

        List<Document> pipeline = List.of(new Document("$facet", facet));
        List<Document> results = globalTemplate().getCollection(collectionName())
            .aggregate(pipeline)
            .into(new ArrayList<>());

        if (results.isEmpty()) {
            return new DashboardStats(0, 0, 0, 0, 0, 0, 0);
        }

        Document doc = results.get(0);
        long total = extractFacetCount(doc, "total");
        long active = extractFacetCount(doc, "active");
        long withUsers = extractFacetCount(doc, "withUsers");
        long currentPeriod = extractFacetCount(doc, "currentPeriod");
        long previousPeriod = extractFacetCount(doc, "previousPeriod");

        long totalUsers = 0;
        long totalTickets = 0;
        List<?> usageList = doc.getList("usage", Document.class, List.of());
        if (!usageList.isEmpty() && usageList.getFirst() instanceof Document usageDoc) {
            totalUsers = extractLong(usageDoc, "totalUsers");
            totalTickets = extractLong(usageDoc, "totalTickets");
        }

        return new DashboardStats(total, active, withUsers, currentPeriod, previousPeriod, totalUsers, totalTickets);
    }

    private long extractFacetCount(Document facets, String key) {
        List<?> list = facets.getList(key, Document.class, List.of());
        if (list.isEmpty()) {
            return 0;
        }
        Object first = list.getFirst();
        if (first instanceof Document doc) {
            Number n = doc.get("n", Number.class);
            return n != null ? n.longValue() : 0;
        }
        return 0;
    }

    public MonitoringServerStats aggregateMonitoringServerStats(Date fiveMinutesAgo, Date oneWeekAgo) {
        Document facet = new Document()
            .append("total", List.of(new Document("$count", "n")))
            .append("active", List.of(
                new Document("$match", new Document(ServerFields.PROVISIONING_STATUS, ProvisioningStatus.COMPLETED.name())
                    .append(ServerFields.EMAIL_VERIFIED, true)),
                new Document("$count", "n")
            ))
            .append("concurrent", List.of(
                new Document("$match", new Document(ServerFields.LAST_ACTIVITY_AT, new Document("$gte", fiveMinutesAgo))),
                new Document("$count", "n")
            ))
            .append("concurrentPlayers", List.of(
                new Document("$match", new Document(ServerFields.LAST_ACTIVITY_AT, new Document("$gte", fiveMinutesAgo))),
                new Document("$group", new Document("_id", null)
                    .append("sum", new Document("$sum", "$" + ServerFields.ONLINE_PLAYER_COUNT)))
            ))
            .append("pending", List.of(
                new Document("$match", new Document(ServerFields.PROVISIONING_STATUS,
                    new Document("$in", List.of(ProvisioningStatus.PENDING.name(), ProvisioningStatus.IN_PROGRESS.name())))),
                new Document("$count", "n")
            ))
            .append("failed", List.of(
                new Document("$match", new Document(ServerFields.PROVISIONING_STATUS, ProvisioningStatus.FAILED.name())),
                new Document("$count", "n")
            ))
            .append("recentRegistrations", List.of(
                new Document("$match", new Document(ServerFields.CREATED_AT, new Document("$gte", oneWeekAgo))),
                new Document("$count", "n")
            ));

        List<Document> pipeline = List.of(new Document("$facet", facet));
        List<Document> results = globalTemplate().getCollection(collectionName())
            .aggregate(pipeline)
            .into(new ArrayList<>());

        if (results.isEmpty()) {
            return new MonitoringServerStats(0, 0, 0, 0, 0, 0, 0);
        }

        Document doc = results.get(0);
        long concurrentPlayers = 0;
        List<?> cpList = doc.getList("concurrentPlayers", Document.class, List.of());
        if (!cpList.isEmpty() && cpList.getFirst() instanceof Document cpDoc) {
            concurrentPlayers = extractLong(cpDoc, "sum");
        }

        return new MonitoringServerStats(
            extractFacetCount(doc, "total"),
            extractFacetCount(doc, "active"),
            extractFacetCount(doc, "concurrent"),
            concurrentPlayers,
            extractFacetCount(doc, "pending"),
            extractFacetCount(doc, "failed"),
            extractFacetCount(doc, "recentRegistrations")
        );
    }

    public record MonitoringServerStats(long total, long active, long concurrent, long concurrentPlayers,
                                         long pending, long failed, long recentRegistrations) {}

    public record DashboardStats(long totalServers, long activeServers, long serversWithData,
                                  long currentPeriodServers, long previousPeriodServers,
                                  long totalUsers, long totalTickets) {}

    public record AIUsageSnapshot(long aiRequestsCurrentPeriod, long maxAiOverageRequests) {}

    public record UsageTotals(long totalUsers, long totalTickets) {}

    public record NameValueResult(String name, int value) {}

    public record DateServersResult(String date, int servers) {}

    public record DateValueResult(String date, long value) {}
}
