package gg.modl.backend.replaylite.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.replaylite.data.ReplayLiteDocument;
import gg.modl.backend.replaylite.data.ReplayLiteLabel;
import gg.modl.backend.replaylite.data.ReplayLiteStatus;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class ReplayLiteMongoRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");

    @Test
    void claimLabelsAtomicallyMatchesOnlyConfirmedUnlabeledUnexpiredReplay() {
        MongoTemplate template = mock(MongoTemplate.class);
        ReplayLiteMongoRepository repository = repository(template);
        when(template.updateFirst(
            org.mockito.ArgumentMatchers.any(Query.class),
            org.mockito.ArgumentMatchers.any(Update.class),
            eq(ReplayLiteDocument.class),
            eq(CollectionName.REPLAY_LITE_REPLAYS)
        )).thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        List<ReplayLiteLabel> submittedLabels = List.of(new ReplayLiteLabel("player", "LEGIT", List.of(), "notes"));

        assertTrue(repository.claimLabels("replay-1", NOW, submittedLabels, "203.0.113.10"));

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(template).updateFirst(
            queryCaptor.capture(),
            updateCaptor.capture(),
            eq(ReplayLiteDocument.class),
            eq(CollectionName.REPLAY_LITE_REPLAYS)
        );
        Document query = queryCaptor.getValue().getQueryObject();
        assertTrue("replay-1".equals(query.get("_id")));
        assertTrue(ReplayLiteStatus.CONFIRMED.equals(query.get("status")));
        assertTrue(query.get("expiresAt", Document.class).containsKey("$gt"));
        List<?> labelsClauses = query.getList("$or", Object.class);
        assertTrue(labelsClauses.stream().map(Document.class::cast)
            .anyMatch(clause -> clause.get("labels") instanceof Document labels && labels.containsKey("$exists")));
        assertTrue(labelsClauses.stream().anyMatch(clause -> ((Document) clause).containsKey("labels") && ((Document) clause).get("labels") == null));
        assertTrue(labelsClauses.stream().map(Document.class::cast)
            .anyMatch(clause -> clause.get("labels") instanceof Document labels && labels.containsKey("$size")));

        Document set = updateCaptor.getValue().getUpdateObject().get("$set", Document.class);
        assertTrue(submittedLabels.equals(set.get("labels")));
        assertTrue("203.0.113.10".equals(set.get("labelIp")));
        assertTrue(NOW.equals(set.get("labeledAt")));
    }

    @Test
    void claimLabelsReturnsFalseWhenNoDocumentWasModified() {
        MongoTemplate template = mock(MongoTemplate.class);
        ReplayLiteMongoRepository repository = repository(template);
        when(template.updateFirst(
            org.mockito.ArgumentMatchers.any(Query.class),
            org.mockito.ArgumentMatchers.any(Update.class),
            eq(ReplayLiteDocument.class),
            eq(CollectionName.REPLAY_LITE_REPLAYS)
        )).thenReturn(UpdateResult.acknowledged(1L, 0L, null));

        assertFalse(repository.claimLabels("replay-1", NOW, List.of(), "203.0.113.10"));
    }

    private ReplayLiteMongoRepository repository(MongoTemplate template) {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        when(tenantMongoAccess.global()).thenReturn(template);
        return new ReplayLiteMongoRepository(tenantMongoAccess);
    }
}
