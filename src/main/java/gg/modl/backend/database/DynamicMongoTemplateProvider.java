package gg.modl.backend.database;

import com.mongodb.client.MongoClient;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DynamicMongoTemplateProvider {
    private static final String GLOBAL_DATABASE_NAME = "modl";

    private final MongoClient mongoClient;
    private final MappingMongoConverter mongoConverter;
    private final ConcurrentMap<String, MongoTemplate> mongoTemplateCache = new ConcurrentHashMap<>();

    public @NotNull MongoTemplate getGlobalDatabase() {
        return getFromDatabaseName(GLOBAL_DATABASE_NAME);
    }

    public @NotNull MongoTemplate getFromDatabaseName(String databaseName) {
        if (databaseName == null) {
            throw new IllegalArgumentException("Database name must not be null");
        }
        return mongoTemplateCache.computeIfAbsent(databaseName, dbName -> {
            SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(mongoClient, dbName);
            return new MongoTemplate(factory, mongoConverter);
        });
    }
}
