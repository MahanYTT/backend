package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.AbstractServerMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayLabel;
import gg.modl.backend.server.data.Server;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class ReplayMongoRepository extends AbstractServerMongoRepository<ReplayDocument> {

    public ReplayMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(ReplayDocument.class, CollectionName.REPLAYS, tenantMongoAccess);
    }

    public Optional<ReplayDocument> findByReplayId(Server server, String replayId) {
        return findById(server, replayId);
    }

    public Optional<ReplayDocument> claimLabels(Server server, String replayId, List<ReplayLabel> labels) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where("_id").is(replayId),
            new Criteria().orOperator(
                Criteria.where("labels").exists(false),
                Criteria.where("labels").size(0)
            )
        ));
        Update update = new Update().set("labels", labels);
        return Optional.ofNullable(findAndModify(server, query, update, FindAndModifyOptions.options().returnNew(true)));
    }

    public List<ReplayDocument> findByTargetUuid(Server server, String targetUuid, int limit) {
        Query query = Query.query(Criteria.where("targetUuid").is(targetUuid));
        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        query.limit(Math.min(limit, 100));
        return find(server, query);
    }

    public List<ReplayDocument> findExpiredCompletedOrFailed(Server server, Date cutoff, int limit) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where("status").in(ReplayDocument.STATUS_COMPLETE, ReplayDocument.STATUS_FAILED),
            Criteria.where("createdAt").lt(cutoff),
            Criteria.where("storageKey").exists(true).nin(null, "")
        ));
        query.with(Sort.by(Sort.Direction.ASC, "createdAt"));
        query.limit(Math.min(limit, 500));
        return find(server, query);
    }

    public long countExpiredWithMissingStorageKey(Server server, Date cutoff) {
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where("status").in(ReplayDocument.STATUS_COMPLETE, ReplayDocument.STATUS_FAILED),
            Criteria.where("createdAt").lt(cutoff),
            new Criteria().orOperator(
                Criteria.where("storageKey").exists(false),
                Criteria.where("storageKey").is(null),
                Criteria.where("storageKey").is("")
            )
        ));
        return count(server, query);
    }

    public boolean deleteByReplayId(Server server, String replayId) {
        return remove(server, Query.query(Criteria.where("_id").is(replayId))).getDeletedCount() > 0;
    }
}
