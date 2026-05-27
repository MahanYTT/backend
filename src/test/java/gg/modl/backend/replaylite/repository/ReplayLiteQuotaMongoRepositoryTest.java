package gg.modl.backend.replaylite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.replaylite.data.ReplayLiteDailyQuotaDocument;
import gg.modl.backend.replaylite.repository.ReplayLiteQuotaMongoRepository.QuotaReservationResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class ReplayLiteQuotaMongoRepositoryTest {
    private static final UUID SERVER_UUID = UUID.fromString("1f5065ba-8f4a-4d6b-bd04-632f88d0be27");
    private static final LocalDate DAY = LocalDate.parse("2026-05-11");
    private static final Instant NOW = Instant.parse("2026-05-11T17:00:00Z");
    private static final String REPLAY_ID = "75f4b741-67df-414c-957b-a8a08222fc30";

    @Test
    void reserveConfirmedUploadAtomicallyIncrementsQuotaDocument() {
        MongoTemplate template = mock(MongoTemplate.class);
        ReplayLiteQuotaMongoRepository repository = repository(template);
        when(template.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(ReplayLiteDailyQuotaDocument.class),
            eq(CollectionName.REPLAY_LITE_DAILY_QUOTAS)
        )).thenReturn(new ReplayLiteDailyQuotaDocument());

        assertEquals(
            QuotaReservationResult.RESERVED,
            repository.reserveConfirmedUpload(SERVER_UUID, DAY, REPLAY_ID, 100, NOW)
        );

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(template).findAndModify(
            queryCaptor.capture(),
            updateCaptor.capture(),
            any(FindAndModifyOptions.class),
            eq(ReplayLiteDailyQuotaDocument.class),
            eq(CollectionName.REPLAY_LITE_DAILY_QUOTAS)
        );
        Document query = queryCaptor.getValue().getQueryObject();
        assertTrue(query.get("_id").toString().contains(SERVER_UUID.toString()));
        assertTrue(query.get("count", Document.class).containsKey("$lt"));
        assertTrue(query.get("replayIds", Document.class).containsKey("$ne"));
        Document inc = updateCaptor.getValue().getUpdateObject().get("$inc", Document.class);
        assertTrue(Integer.valueOf(1).equals(inc.get("count")));
        Document addToSet = updateCaptor.getValue().getUpdateObject().get("$addToSet", Document.class);
        assertTrue(REPLAY_ID.equals(addToSet.get("replayIds")));
    }

    @Test
    void reserveConfirmedUploadRetriesAfterConcurrentFirstUseDuplicateInsert() {
        MongoTemplate template = mock(MongoTemplate.class);
        ReplayLiteQuotaMongoRepository repository = repository(template);
        when(template.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(ReplayLiteDailyQuotaDocument.class),
            eq(CollectionName.REPLAY_LITE_DAILY_QUOTAS)
        ))
            .thenThrow(new DuplicateKeyException("quota already exists"))
            .thenReturn(new ReplayLiteDailyQuotaDocument());

        assertEquals(
            QuotaReservationResult.RESERVED,
            repository.reserveConfirmedUpload(SERVER_UUID, DAY, REPLAY_ID, 100, NOW)
        );
        verify(template, times(2)).findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(ReplayLiteDailyQuotaDocument.class),
            eq(CollectionName.REPLAY_LITE_DAILY_QUOTAS)
        );
    }

    @Test
    void reserveConfirmedUploadReturnsLimitReachedWhenRetryFindsQuotaFull() {
        MongoTemplate template = mock(MongoTemplate.class);
        ReplayLiteQuotaMongoRepository repository = repository(template);
        when(template.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(ReplayLiteDailyQuotaDocument.class),
            eq(CollectionName.REPLAY_LITE_DAILY_QUOTAS)
        ))
            .thenThrow(new DuplicateKeyException("quota already exists"))
            .thenReturn(null);

        assertEquals(
            QuotaReservationResult.LIMIT_REACHED,
            repository.reserveConfirmedUpload(SERVER_UUID, DAY, REPLAY_ID, 100, NOW)
        );
    }

    @Test
    void reserveConfirmedUploadReturnsAlreadyReservedForDuplicateReplay() {
        MongoTemplate template = mock(MongoTemplate.class);
        ReplayLiteQuotaMongoRepository repository = repository(template);
        ReplayLiteDailyQuotaDocument existing = new ReplayLiteDailyQuotaDocument();
        existing.setReplayIds(List.of(REPLAY_ID));
        when(template.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(ReplayLiteDailyQuotaDocument.class),
            eq(CollectionName.REPLAY_LITE_DAILY_QUOTAS)
        )).thenReturn(null);
        when(template.findById(
            any(String.class),
            eq(ReplayLiteDailyQuotaDocument.class),
            eq(CollectionName.REPLAY_LITE_DAILY_QUOTAS)
        )).thenReturn(existing);

        assertEquals(
            QuotaReservationResult.ALREADY_RESERVED,
            repository.reserveConfirmedUpload(SERVER_UUID, DAY, REPLAY_ID, 100, NOW)
        );
    }

    @Test
    void releaseConfirmedUploadDecrementsExistingReservation() {
        MongoTemplate template = mock(MongoTemplate.class);
        ReplayLiteQuotaMongoRepository repository = repository(template);

        repository.releaseConfirmedUpload(SERVER_UUID, DAY, REPLAY_ID, NOW);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(template).updateFirst(
            queryCaptor.capture(),
            updateCaptor.capture(),
            eq(ReplayLiteDailyQuotaDocument.class),
            eq(CollectionName.REPLAY_LITE_DAILY_QUOTAS)
        );
        Document query = queryCaptor.getValue().getQueryObject();
        assertTrue(REPLAY_ID.equals(query.get("replayIds")));
        Document inc = updateCaptor.getValue().getUpdateObject().get("$inc", Document.class);
        assertTrue(Integer.valueOf(-1).equals(inc.get("count")));
        Document pull = updateCaptor.getValue().getUpdateObject().get("$pull", Document.class);
        assertTrue(REPLAY_ID.equals(pull.get("replayIds")));
    }

    private ReplayLiteQuotaMongoRepository repository(MongoTemplate template) {
        TenantMongoAccess tenantMongoAccess = mock(TenantMongoAccess.class);
        when(tenantMongoAccess.global()).thenReturn(template);
        return new ReplayLiteQuotaMongoRepository(tenantMongoAccess);
    }
}
