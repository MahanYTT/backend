package gg.modl.backend.knowledgebase.data;

import gg.modl.backend.database.CollectionName;
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

@Document(collection = CollectionName.KNOWLEDGEBASE_CATEGORIES)
@GenerateMongoFields
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgebaseCategory {
    @Id
    private String id;

    @Field("name")
    private String name;
    @Field("slug")
    private String slug;
    @Field("description")
    private String description;
    @Field("ordinal")
    private int ordinal;
    @Field("isVisible")
    private boolean isVisible;

    @Field("createdAt")
    private Date createdAt;
    @Field("updatedAt")
    private Date updatedAt;
}
