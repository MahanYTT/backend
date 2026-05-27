package gg.modl.backend.audit.data;

import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import gg.modl.backend.database.mongo.codegen.MongoFieldAlias;
import gg.modl.backend.database.mongo.codegen.MongoFieldAliases;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateMongoFields
@MongoFieldAliases({
    @MongoFieldAlias(name = "METADATA_ROLLED_BACK", path = "metadata.rolledBack"),
    @MongoFieldAlias(name = "METADATA_ROLLBACK_DATE", path = "metadata.rollbackDate"),
    @MongoFieldAlias(name = "METADATA_ROLLBACK_BY", path = "metadata.rollbackBy"),
    @MongoFieldAlias(name = "METADATA_CAN_ROLLBACK", path = "metadata.canRollback")
})
public class AuditLog {
    @Id
    private String id;

    @Field("created")
    private Date created;
    @Field("level")
    private String level;
    @Field("source")
    private String source;
    @Field("description")
    private String description;
    @Field("metadata")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
