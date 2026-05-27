package gg.modl.backend.alert.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = CollectionName.SYSTEM_ALERTS)
@GenerateMongoFields
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemAlert {
    @Id
    private String id;

    @Field("message")
    private String message;

    @Field("severity")
    private SystemAlertSeverity severity;

    @Field("audience")
    private SystemAlertAudience audience;

    @Field("expiresAt")
    private Date expiresAt;

    @Field("createdAt")
    private Date createdAt;

    @Field("updatedAt")
    private Date updatedAt;

    @Field("createdBy")
    private String createdBy;

    @Field("updatedBy")
    private String updatedBy;
}
