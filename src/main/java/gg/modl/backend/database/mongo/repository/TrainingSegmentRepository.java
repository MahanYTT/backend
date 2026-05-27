package gg.modl.backend.database.mongo.repository;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.replay.data.TrainingSegmentDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TrainingSegmentRepository {
    private static final String DB_NAME = "training_data";
    private static final String COLLECTION = CollectionName.TRAINING_SEGMENTS;

    private final TenantMongoAccess tenantMongoAccess;

    public void save(TrainingSegmentDocument doc) {
        tenantMongoAccess.forDatabase(DB_NAME).save(doc, COLLECTION);
    }
}
