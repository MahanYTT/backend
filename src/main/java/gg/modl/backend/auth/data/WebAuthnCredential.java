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

@Document(collection = CollectionName.WEBAUTHN_CREDENTIALS)
@GenerateMongoFields
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WebAuthnCredential {
    @Id
    private String id;

    @Field("email")
    private String email;

    @Field("credentialId")
    private String credentialId;

    @Field("publicKeyCose")
    private byte[] publicKeyCose;

    @Field("signatureCount")
    private long signatureCount;

    @Field("userHandle")
    private String userHandle;

    @Field("name")
    private String name;

    @Field("createdAt")
    private Date createdAt;

    @Field("lastUsedAt")
    private Date lastUsedAt;
}
