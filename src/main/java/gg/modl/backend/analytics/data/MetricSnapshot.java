package gg.modl.backend.analytics.data;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = CollectionName.METRIC_SNAPSHOTS)
@Getter
@Setter
@GenerateMongoFields
public class MetricSnapshot {
    @Id
    private String id;

    @Field
    private Date date; // truncated to hour (UTC)

    @Field
    private long activeServers;

    @Field
    private Date createdAt;
}
