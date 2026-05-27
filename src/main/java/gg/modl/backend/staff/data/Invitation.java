package gg.modl.backend.staff.data;

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

@Document(collection = CollectionName.INVITATIONS)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@GenerateMongoFields
public class Invitation {
    @Id
    private String id;

    @Field("email")
    private String email;

    @Field("role")
    private String role;

    @Field("token")
    private String token;

    @Field("expiresAt")
    private Date expiresAt;

    @Field("createdAt")
    private Date createdAt;

    @Field("updatedAt")
    private Date updatedAt;
}
