package gg.modl.backend.staff.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import gg.modl.backend.database.mongo.codegen.MongoFieldAlias;
import gg.modl.backend.database.mongo.codegen.MongoFieldAliases;
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
@JsonIgnoreProperties(ignoreUnknown = true)
@GenerateMongoFields
@MongoFieldAliases({
    @MongoFieldAlias(name = "SUBSCRIBED_TICKET_TICKET_ID", path = "subscribedTickets.ticketId"),
    @MongoFieldAlias(name = "SUBSCRIBED_TICKET_ACTIVE", path = "subscribedTickets.active"),
    @MongoFieldAlias(name = "SUBSCRIBED_TICKET_POS_ACTIVE", path = "subscribedTickets.$.active"),
    @MongoFieldAlias(name = "SUBSCRIBED_TICKET_POS_LAST_READ_AT", path = "subscribedTickets.$.lastReadAt")
})
public class Staff {
    @Id
    private String id;

    @Field("email")
    private String email;

    @Field("username")
    private String username;

    @Field("role")
    private String role;

    @Field("assignedMinecraftUuid")
    private String assignedMinecraftUuid;

    @Field("assignedMinecraftUsername")
    private String assignedMinecraftUsername;

    @Field("language")
    @Builder.Default
    private String language = "en";

    @Field("dateFormat")
    @Builder.Default
    private String dateFormat = "MM/DD/YYYY";

    @Field("subscribedTickets")
    @Builder.Default
    private List<TicketSubscription> subscribedTickets = new ArrayList<>();

    @Field("ticketSubscriptionSettings")
    private TicketSubscriptionSettings ticketSubscriptionSettings;

    @Field("twoFactorToken")
    private String twoFactorToken;

    @Field("twoFactorTokenIp")
    private String twoFactorTokenIp;

    @Field("twoFactorTokenCreatedAt")
    private Long twoFactorTokenCreatedAt;

    @Field("twoFactorPendingDelivery")
    @Builder.Default
    private boolean twoFactorPendingDelivery = false;

    @Field("twoFactorSessionIp")
    private String twoFactorSessionIp;

    @Field("twoFactorSessionExpiresAt")
    private Long twoFactorSessionExpiresAt;

    @Field("lastSeen")
    private Date lastSeen;

    @Field("createdAt")
    private Date createdAt;

    @Field("updatedAt")
    private Date updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TicketSubscription {
        @Field("ticketId")
        private String ticketId;
        @Field("subscribedAt")
        private Date subscribedAt;
        @Field("lastReadAt")
        private Date lastReadAt;
        @Field("active")
        @Builder.Default
        private boolean active = true;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TicketSubscriptionSettings {
        @Field("enabled")
        @Builder.Default
        private boolean enabled = true;
        @Field("frequency")
        private String frequency;
        @Field("emailNotifications")
        private NotificationSettings emailNotifications;
        @Field("pushNotifications")
        private NotificationSettings pushNotifications;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NotificationSettings {
        @Field("enabled")
        @Builder.Default
        private boolean enabled = false;
        @Field("newTickets")
        @Builder.Default
        private boolean newTickets = false;
        @Field("ticketReplies")
        @Builder.Default
        private boolean ticketReplies = false;
        @Field("ticketStatusChanges")
        @Builder.Default
        private boolean ticketStatusChanges = false;
        @Field("ticketAssignments")
        @Builder.Default
        private boolean ticketAssignments = false;
        @Field("subscribedTypes")
        @Builder.Default
        private List<String> subscribedTypes = new ArrayList<>();
    }
}
