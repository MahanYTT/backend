package gg.modl.backend.ticket.data;

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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = CollectionName.TICKET_VERIFICATIONS)
@GenerateMongoFields
public class TicketVerification {
    @Id
    private String id;

    @Field("ticketId")
    private String ticketId;
    @Field("token")
    private String token;
    @Field("codeHash")
    private String codeHash;
    @Field("email")
    private String email;

    @Field("failedAttempts")
    private int failedAttempts;

    @Field("expiresAt")
    private Date expiresAt;
}
