package gg.modl.backend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class TenantMigrationServiceTest {
    @Test
    void appliesLowercaseUuidMigrationWhenMarkerMissing() {
        MongoTemplate template = mock(MongoTemplate.class);
        MongoCollection<Document> migrations = mockMigrationsCollection(template, null);
        MongoCollection<Document> tickets = mockTicketsCollection(template, 3L, 2L);

        TenantMigrationService service = new TenantMigrationService(
            mock(TenantMongoAccess.class), mock(ServerMongoRepository.class));
        service.applyMigrationsForTenant(template);

        ArgumentCaptor<Bson> filterCaptor = ArgumentCaptor.forClass(Bson.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Bson>> pipelineCaptor = ArgumentCaptor.forClass(List.class);
        verify(tickets).updateMany(filterCaptor.capture(), pipelineCaptor.capture());

        String filterJson = filterCaptor.getValue()
            .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry())
            .toJson();
        assertThat(filterJson).contains("creatorUuid");
        assertThat(filterJson).contains("reportedPlayerUuid");
        assertThat(filterJson).contains("$or");

        List<Bson> pipeline = pipelineCaptor.getValue();
        assertThat(pipeline).hasSize(1);
        String pipelineJson = pipeline.get(0)
            .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry())
            .toJson();
        assertThat(pipelineJson).contains("$set");
        assertThat(pipelineJson).contains("$toLower");
        assertThat(pipelineJson).contains("$creatorUuid");
        assertThat(pipelineJson).contains("$reportedPlayerUuid");

        ArgumentCaptor<Bson> markerFilter = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> markerUpdate = ArgumentCaptor.forClass(Bson.class);
        verify(migrations).updateOne(markerFilter.capture(), markerUpdate.capture(), any(UpdateOptions.class));
        String markerFilterJson = markerFilter.getValue()
            .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry())
            .toJson();
        assertThat(markerFilterJson).contains(TenantMigrationService.LOWERCASE_TICKET_UUIDS_MIGRATION_ID);
        String markerUpdateJson = markerUpdate.getValue()
            .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry())
            .toJson();
        assertThat(markerUpdateJson).contains("appliedAt");
    }

    @Test
    void skipsMigrationWhenMarkerAlreadyExists() {
        MongoTemplate template = mock(MongoTemplate.class);
        Document existingMarker = new Document("_id", TenantMigrationService.LOWERCASE_TICKET_UUIDS_MIGRATION_ID);
        MongoCollection<Document> migrations = mockMigrationsCollection(template, existingMarker);
        MongoCollection<Document> tickets = mockTicketsCollection(template, 0L, 0L);

        TenantMigrationService service = new TenantMigrationService(
            mock(TenantMongoAccess.class), mock(ServerMongoRepository.class));
        service.applyMigrationsForTenant(template);

        verify(tickets, never()).updateMany(any(Bson.class), anyList());
        verify(migrations, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    private MongoCollection<Document> mockMigrationsCollection(MongoTemplate template, Document existingMarker) {
        @SuppressWarnings("unchecked")
        MongoCollection<Document> migrations = mock(MongoCollection.class);
        @SuppressWarnings("unchecked")
        FindIterable<Document> findIterable = mock(FindIterable.class);
        lenient().when(template.getCollection(CollectionName.TENANT_MIGRATIONS)).thenReturn(migrations);
        lenient().when(migrations.find(any(Bson.class))).thenReturn(findIterable);
        lenient().when(findIterable.first()).thenReturn(existingMarker);
        lenient().when(migrations.updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class)))
            .thenReturn(UpdateResult.acknowledged(0, 1L, null));
        return migrations;
    }

    private MongoCollection<Document> mockTicketsCollection(MongoTemplate template, long matched, long modified) {
        @SuppressWarnings("unchecked")
        MongoCollection<Document> tickets = mock(MongoCollection.class);
        com.mongodb.client.MongoDatabase database = mock(com.mongodb.client.MongoDatabase.class);
        lenient().when(template.getCollection(CollectionName.TICKETS)).thenReturn(tickets);
        lenient().when(template.getDb()).thenReturn(database);
        lenient().when(database.getName()).thenReturn("tenant_db");
        lenient().when(tickets.updateMany(any(Bson.class), anyList()))
            .thenReturn(UpdateResult.acknowledged(matched, modified, null));
        return tickets;
    }
}
