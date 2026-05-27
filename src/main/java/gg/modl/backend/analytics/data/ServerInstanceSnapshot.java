package gg.modl.backend.analytics.data;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = CollectionName.SERVER_INSTANCE_SNAPSHOTS)
@Getter
@Setter
@GenerateMongoFields
public class ServerInstanceSnapshot {
    @Id
    private String id;

    @Field
    private Date date;

    @Field
    private List<ServerEntry> servers = new ArrayList<>();

    @Field
    private Date createdAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerEntry {
        private String serverId;
        private String serverName;
        private int playerCount;
        private String platform;
        private String version;
        private String ipAddress;
        private String pluginVersion;
    }
}
