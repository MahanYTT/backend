package gg.modl.backend.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared MongoDB Testcontainer for integration tests. The container is started once per JVM
 * (static initializer) and reused across test classes, which is much faster than restarting
 * Mongo per class. Each test class is responsible for cleaning the collections it touches.
 *
 * <p>Subclasses get a fully wired Spring Data MongoDB connection via {@link #registerProperties}.
 * Combine with {@code @DataMongoTest} for slice tests or {@code @SpringBootTest} for full
 * integration runs. Spring Data's index auto-creation stays off (matching production); tests
 * that need indexes should invoke the index bootstrap explicitly.
 */
@ActiveProfiles("test")
public abstract class AbstractMongoIntegrationTest {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0.14");

    protected static final MongoDBContainer MONGO_CONTAINER = new MongoDBContainer(MONGO_IMAGE)
        .withReuse(true);

    static {
        MONGO_CONTAINER.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Append uuidRepresentation=standard so the MongoClient built from this URI knows how
        // to encode java.util.UUID fields. The Spring Data property
        // spring.data.mongodb.uuid-representation alone is not enough when the project's
        // DynamicMongoTemplateProvider wraps the autowired MongoClient.
        String uri = MONGO_CONTAINER.getReplicaSetUrl("modl-it") + "?uuidRepresentation=standard";
        registry.add("spring.data.mongodb.uri", () -> uri);
        registry.add("spring.mongodb.uri", () -> uri);
        registry.add("spring.data.mongodb.auto-index-creation", () -> "false");
        registry.add("spring.data.mongodb.uuid-representation", () -> "standard");
    }
}
