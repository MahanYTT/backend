package gg.modl.backend.auth.session;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = CollectionName.SESSIONS)
@GenerateMongoFields
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthSessionData {
    @Id
    private String id;

    @Field("email")
    private String email;

    @Field("createdAt")
    private Date createdAt;

    @Field("expiresAt")
    private Date expiresAt;

    @Field("ipAddress")
    private String ipAddress;

    @Field("userAgent")
    private String userAgent;
}
