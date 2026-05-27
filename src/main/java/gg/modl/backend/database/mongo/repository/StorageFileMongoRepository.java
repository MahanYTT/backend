package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.AbstractServerMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.data.StorageFileDocument;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class StorageFileMongoRepository extends AbstractServerMongoRepository<StorageFileDocument> {

    public StorageFileMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(StorageFileDocument.class, CollectionName.STORAGE_FILES, tenantMongoAccess);
    }

    public Optional<StorageFileDocument> findByKey(Server server, String key) {
        return findOne(server, Query.query(Criteria.where("key").is(key)));
    }

    public List<StorageFileDocument> findByKeyPrefix(Server server, String prefix, int limit) {
        Query query = Query.query(Criteria.where("key").regex("^" + escapeRegex(prefix)))
            .with(Sort.by(Sort.Direction.DESC, "createdAt"))
            .limit(limit);
        return find(server, query);
    }

    public void deleteByKey(Server server, String key) {
        remove(server, Query.query(Criteria.where("key").is(key)));
    }

    public void deleteByKeys(Server server, List<String> keys) {
        remove(server, Query.query(Criteria.where("key").in(keys)));
    }

    public List<StorageFileDocument> findByKeys(Server server, List<String> keys) {
        return find(server, Query.query(Criteria.where("key").in(keys)));
    }

    public void deleteByKeyNotIn(Server server, List<String> keys) {
        remove(server, Query.query(Criteria.where("key").nin(keys)));
    }

    public Map<String, Long> aggregateStorageByCategory(Server server) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.group("category").sum("size").as("totalSize")
        );

        AggregationResults<Document> results = aggregate(server, aggregation, Document.class);

        Map<String, Long> byType = new HashMap<>();
        byType.put("ticket", 0L);
        byType.put("evidence", 0L);
        byType.put("logs", 0L);
        byType.put("backup", 0L);
        byType.put("replay", 0L);
        byType.put("other", 0L);

        for (Document doc : results.getMappedResults()) {
            String category = doc.getString("_id");
            long totalSize = doc.get("totalSize", Number.class).longValue();
            byType.put(category, totalSize);
        }

        return byType;
    }

    public long sumTotalSize(Server server) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.group().sum("size").as("totalSize")
        );

        AggregationResults<Document> results = aggregate(server, aggregation, Document.class);

        Document result = results.getUniqueMappedResult();
        if (result == null) {
            return 0L;
        }
        return result.get("totalSize", Number.class).longValue();
    }

    private String escapeRegex(String input) {
        return input.replaceAll("([\\\\^$.|?*+()\\[\\]{}])", "\\\\$1");
    }
}
