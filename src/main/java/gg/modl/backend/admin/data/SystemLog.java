package gg.modl.backend.admin.data;

import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Getter
@Setter
@Document(collection = "system_logs")
@GenerateMongoFields
public class SystemLog {
    @Id
    private String id;
    @Field("level")
    private String level;
    @Field("message")
    private String message;
    @Field("source")
    private String source;
    @Field("category")
    private String category;
    @Field("serverId")
    private String serverId;
    @Field("metadata")
    private Map<String, Object> metadata = new HashMap<>();
    @Field("resolved")
    private boolean resolved;
    @Field("resolvedBy")
    private String resolvedBy;
    @Field("resolvedAt")
    private Date resolvedAt;
    @Field("timestamp")
    private Date timestamp;

    public SystemLog(String level, String message, String source) {
        this();
        this.level = level;
        this.message = message;
        this.source = source;
    }

    public SystemLog() {
        this.timestamp = new Date();
        this.resolved = false;
    }
}
