package gg.modl.backend.role.data;

import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
@GenerateMongoFields
public class StaffRole {
    @Id
    private String id;

    @Field("name")
    private String name;

    @Field("description")
    private String description;

    @Field("permissions")
    @Builder.Default
    private List<String> permissions = new ArrayList<>();

    @Field("isDefault")
    @Builder.Default
    private boolean isDefault = false;

    @Field("order")
    @Builder.Default
    private int order = 999;

    @Field("createdAt")
    private Date createdAt;

    @Field("updatedAt")
    private Date updatedAt;
}
