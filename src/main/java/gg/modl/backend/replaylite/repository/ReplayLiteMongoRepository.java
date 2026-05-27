package gg.modl.backend.replaylite.repository;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.AbstractGlobalMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.replaylite.data.ReplayLiteDocument;
import gg.modl.backend.replaylite.data.ReplayLiteLabel;
import gg.modl.backend.replaylite.data.ReplayLiteStatus;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class ReplayLiteMongoRepository extends AbstractGlobalMongoRepository<ReplayLiteDocument> {

    public ReplayLiteMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(ReplayLiteDocument.class, CollectionName.REPLAY_LITE_REPLAYS, tenantMongoAccess);
    }

    public Optional<ReplayLiteDocument> findByReplayId(String replayId) {
        return findById(replayId);
    }

    public long countConfirmedForServerBetween(UUID pluginServerUuid, Instant startInclusive, Instant endExclusive) {
        Query query = Query.query(Criteria.where("pluginServerUuid").is(pluginServerUuid)
            .and("status").is(ReplayLiteStatus.CONFIRMED)
            .and("confirmedAt").gte(startInclusive).lt(endExclusive));
        return count(query);
    }

    public long countPendingForServerSince(UUID pluginServerUuid, Instant staleCutoff) {
        Query query = Query.query(Criteria.where("pluginServerUuid").is(pluginServerUuid)
            .and("status").is(ReplayLiteStatus.PENDING)
            .and("createdAt").gte(staleCutoff));
        return count(query);
    }

    public List<ReplayLiteDocument> findExpiredConfirmed(Instant now, int limit) {
        Query query = Query.query(Criteria.where("status").is(ReplayLiteStatus.CONFIRMED)
            .and("expiresAt").lte(now));
        query.limit(limit);
        query.with(Sort.by(Sort.Direction.ASC, "expiresAt"));
        return find(query);
    }

    public List<ReplayLiteDocument> findStalePending(Instant staleCutoff, int limit) {
        Query query = Query.query(Criteria.where("status").is(ReplayLiteStatus.PENDING)
            .and("createdAt").lte(staleCutoff));
        query.limit(limit);
        query.with(Sort.by(Sort.Direction.ASC, "createdAt"));
        return find(query);
    }

    public ReplayLiteDocument saveEntity(ReplayLiteDocument document) {
        return super.saveEntity(document);
    }

    public boolean claimLabels(String replayId, Instant now, List<ReplayLiteLabel> labels, String labelIp) {
        Query query = Query.query(Criteria.where("_id").is(replayId)
            .and("status").is(ReplayLiteStatus.CONFIRMED)
            .and("expiresAt").gt(now)
            .orOperator(
                Criteria.where("labels").exists(false),
                Criteria.where("labels").is(null),
                Criteria.where("labels").size(0)
            ));
        Update update = new Update()
            .set("labels", labels)
            .set("labelIp", labelIp)
            .set("labeledAt", now);

        UpdateResult result = updateFirst(query, update);
        return result.getModifiedCount() == 1;
    }

    public boolean confirmPendingUpload(
        String replayId,
        long confirmedSize,
        Instant confirmedAt,
        Instant expiresAt,
        String confirmIp,
        Instant freshCreatedAfter
    ) {
        Query query = Query.query(Criteria.where("_id").is(replayId)
            .and("status").is(ReplayLiteStatus.PENDING)
            .and("createdAt").gt(freshCreatedAfter));
        Update update = new Update()
            .set("status", ReplayLiteStatus.CONFIRMED)
            .set("confirmedSize", confirmedSize)
            .set("confirmedAt", confirmedAt)
            .set("expiresAt", expiresAt)
            .set("confirmIp", confirmIp);

        UpdateResult result = updateFirst(query, update);
        return result.getModifiedCount() == 1;
    }

    public void deleteByReplayId(String replayId) {
        remove(Query.query(Criteria.where("_id").is(replayId)));
    }
}
