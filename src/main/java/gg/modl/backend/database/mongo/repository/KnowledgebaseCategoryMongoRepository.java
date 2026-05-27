package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.AbstractServerMongoRepository;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.fields.KnowledgebaseCategoryFields;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.server.data.Server;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgebaseCategoryMongoRepository extends AbstractServerMongoRepository<KnowledgebaseCategory> {
    public KnowledgebaseCategoryMongoRepository(TenantMongoAccess tenantMongoAccess) {
        super(KnowledgebaseCategory.class, CollectionName.KNOWLEDGEBASE_CATEGORIES, tenantMongoAccess);
    }

    public boolean hasAny(Server server) {
        return count(server, new Query()) > 0;
    }

    public List<KnowledgebaseCategory> findAllOrdered(Server server) {
        return find(server, new Query().with(Sort.by(Sort.Direction.ASC, KnowledgebaseCategoryFields.ORDINAL)));
    }

    public List<KnowledgebaseCategory> findVisibleOrdered(Server server) {
        Query query = Query.query(Criteria.where(KnowledgebaseCategoryFields.IS_VISIBLE).is(true))
            .with(Sort.by(Sort.Direction.ASC, KnowledgebaseCategoryFields.ORDINAL));
        return find(server, query);
    }

    public Optional<KnowledgebaseCategory> findByCategoryId(Server server, String id) {
        return findById(server, id);
    }

    public int findMaxOrdinal(Server server) {
        Query query = new Query()
            .with(Sort.by(Sort.Direction.DESC, KnowledgebaseCategoryFields.ORDINAL))
            .limit(1);
        return findOne(server, query)
            .map(KnowledgebaseCategory::getOrdinal)
            .orElse(-1);
    }

    public Optional<KnowledgebaseCategory> updateCategory(
        Server server,
        String id,
        String name,
        String slug,
        String description,
        Boolean isVisible,
        Date updatedAt
    ) {
        Update update = new Update().set(KnowledgebaseCategoryFields.UPDATED_AT, updatedAt);
        if (name != null) {
            update.set(KnowledgebaseCategoryFields.NAME, name);
        }
        if (slug != null) {
            update.set(KnowledgebaseCategoryFields.SLUG, slug);
        }
        if (description != null) {
            update.set(KnowledgebaseCategoryFields.DESCRIPTION, description);
        }
        if (isVisible != null) {
            update.set(KnowledgebaseCategoryFields.IS_VISIBLE, isVisible);
        }

        KnowledgebaseCategory updated = findAndModify(
            server,
            Query.query(Criteria.where(KnowledgebaseCategoryFields.ID).is(id)),
            update,
            FindAndModifyOptions.options().returnNew(true)
        );
        return Optional.ofNullable(updated);
    }

    public boolean deleteByCategoryId(Server server, String id) {
        return remove(server, Query.query(Criteria.where(KnowledgebaseCategoryFields.ID).is(id))).getDeletedCount() > 0;
    }

    public void reorderCategories(Server server, List<String> ids) {
        if (ids.isEmpty()) return;

        MongoTemplate template = serverTemplate(server);
        BulkOperations bulk = template.bulkOps(BulkOperations.BulkMode.UNORDERED, collectionName());
        for (int index = 0; index < ids.size(); index++) {
            Query query = Query.query(Criteria.where("_id").is(new ObjectId(ids.get(index))));
            Update update = new Update().set(KnowledgebaseCategoryFields.ORDINAL, index);
            bulk.updateOne(query, update);
        }
        bulk.execute();
    }
}
