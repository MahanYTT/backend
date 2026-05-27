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

@Document(collection = CollectionName.KNOWLEDGEBASE_ARTICLES)
@GenerateMongoFields
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgebaseArticle {
    @Id
    private String id;

    @Field("title")
    private String title;
    @Field("slug")
    private String slug;
    @Field("content")
    private String content;
    @Field("categoryId")
    private String categoryId;
    @Field("ordinal")
    private int ordinal;
    @Field("isVisible")
    private boolean isVisible;
    @Field("createdAt")
    private Date createdAt;
    @Field("updatedAt")
    private Date updatedAt;
}
