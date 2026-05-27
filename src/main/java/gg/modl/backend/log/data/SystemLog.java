package gg.modl.backend.log.data;

import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@GenerateMongoFields(className = "ServerLogFields")
public class SystemLog {
    @Id
    private String id;

    @Field("description")
    private String description;

    @Field("level")
    @Builder.Default
    private String level = "info";

    @Field("source")
    @Builder.Default
    private String source = "system";

    @Field("created")
    private Date created;
}
