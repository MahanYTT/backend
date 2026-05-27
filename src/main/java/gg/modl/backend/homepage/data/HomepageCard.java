package gg.modl.backend.homepage.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

@Document(collection = CollectionName.HOMEPAGE_CARDS)
@GenerateMongoFields
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HomepageCard {
    @Id
    private String id;

    @Field("title")
    private String title;
    @Field("description")
    private String description;
    @Field("icon")
    private String icon;

    @Field("iconColor")
    private String iconColor;

    @Field("actionType")
    private String actionType;

    @Field("actionUrl")
    private String actionUrl;

    @Field("actionButtonText")
    private String actionButtonText;

    @Field("categoryId")
    private String categoryId;

    @Field("backgroundColor")
    private String backgroundColor;

    @Field("ordinal")
    private int ordinal;

    @Field("isEnabled")
    private boolean isEnabled;

    @Field("createdAt")
    private Date createdAt;

    @Field("updatedAt")
    private Date updatedAt;
}
