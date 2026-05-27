package gg.modl.backend.auth.data;

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

@Document(collection = CollectionName.WEBAUTHN_CHALLENGES)
@GenerateMongoFields
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WebAuthnChallenge {
    @Id
    private String id;

    @Field("challengeJson")
    private String challengeJson;

    @Field("email")
    private String email;

    @Field("expiresAt")
    private Date expiresAt;
}
