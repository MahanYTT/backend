package gg.modl.backend.admin.data;

import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import gg.modl.backend.email.EmailAddressUtil;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Getter
@Setter
@Document(collection = "admin_users")
@GenerateMongoFields
public class AdminUser {
    @Id
    private String id;
    @Field("email")
    private String email;
    @Field("loggedInIps")
    private List<String> loggedInIps = new ArrayList<>();
    @Field("lastActivityAt")
    private Date lastActivityAt;
    @Field("createdAt")
    private Date createdAt;

    public AdminUser(String email) {
        this();
        this.email = EmailAddressUtil.normalize(email);
    }

    public AdminUser() {
        this.createdAt = new Date();
        this.lastActivityAt = new Date();
    }
}
