package gg.modl.backend.admin.data;

import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Getter
@Setter
@Document(collection = "systemprompts")
@GenerateMongoFields
public class SystemPrompt {
    @Id
    private String id;
    @Field("prompt")
    private String prompt;
    @Field("isActive")
    private boolean isActive = true;
    @Field("createdAt")
    private Date createdAt = new Date();
    @Field("updatedAt")
    private Date updatedAt = new Date();
}
