package gg.modl.backend.player.data;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import gg.modl.backend.database.mongo.codegen.MongoFieldAlias;
import gg.modl.backend.database.mongo.codegen.MongoFieldAliases;
import gg.modl.backend.player.data.punishment.Punishment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Document(collection = CollectionName.PLAYERS)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@GenerateMongoFields
@MongoFieldAliases({
    @MongoFieldAlias(name = "USERNAME", path = "usernames.username"),
    @MongoFieldAlias(name = "NOTE_ID", path = "notes.id"),
    @MongoFieldAlias(name = "IP_ADDRESS", path = "ipAddresses.ipAddress"),
    @MongoFieldAlias(name = "PUNISHMENT_ID", path = "punishments.id"),
    @MongoFieldAlias(name = "PUNISHMENT_ISSUED", path = "punishments.issued"),
    @MongoFieldAlias(name = "PUNISHMENT_ISSUER_NAME", path = "punishments.issuerName"),
    @MongoFieldAlias(name = "PUNISHMENT_TYPE_ORDINAL", path = "punishments.typeOrdinal"),
    @MongoFieldAlias(name = "PUNISHMENT_ISSUER_ID", path = "punishments.issuerId"),
    @MongoFieldAlias(name = "PUNISHMENT_DATA_STATUS", path = "punishments.data.status"),
    @MongoFieldAlias(name = "PUNISHMENT_DATA_REASON", path = "punishments.data.reason"),
    @MongoFieldAlias(name = "PUNISHMENT_DATA_DURATION", path = "punishments.data.duration"),
    @MongoFieldAlias(name = "IP_FIRST_LOGIN", path = "ipAddresses.firstLogin"),
    @MongoFieldAlias(name = "DATA_IS_ONLINE", path = "data.isOnline"),
    @MongoFieldAlias(name = "DATA_TOTAL_PLAYTIME_SECONDS", path = "data.totalPlaytimeSeconds"),
    @MongoFieldAlias(name = "DATA_LAST_SKIN_HASH", path = "data.lastSkinHash"),
    @MongoFieldAlias(name = "DATA_FIRST_JOIN", path = "data.firstJoin"),
    @MongoFieldAlias(name = "DATA_LAST_LOGIN", path = "data.lastLogin"),
    @MongoFieldAlias(name = "DATA_LAST_LOGOUT", path = "data.lastLogout"),
    @MongoFieldAlias(name = "DATA_LAST_SERVER", path = "data.lastServer"),
    @MongoFieldAlias(name = "DATA_PENDING_NOTIFICATIONS", path = "data.pendingNotifications"),
    @MongoFieldAlias(name = "DATA_LINKED_ACCOUNTS", path = "data.linkedAccounts"),
    @MongoFieldAlias(name = "DATA_LAST_LINKED_UPDATE", path = "data.lastLinkedUpdate"),
    @MongoFieldAlias(name = "IP_COUNTRY", path = "ipAddresses.$.country"),
    @MongoFieldAlias(name = "IP_REGION", path = "ipAddresses.$.region"),
    @MongoFieldAlias(name = "IP_ASN", path = "ipAddresses.$.asn"),
    @MongoFieldAlias(name = "IP_PROXY", path = "ipAddresses.$.proxy"),
    @MongoFieldAlias(name = "IP_HOSTING", path = "ipAddresses.$.hosting"),
    @MongoFieldAlias(name = "IP_LOGINS", path = "ipAddresses.$.logins"),
    @MongoFieldAlias(name = "PUNISHMENT_MODIFICATIONS", path = "punishments.$.modifications"),
    @MongoFieldAlias(name = "PUNISHMENT_NOTES", path = "punishments.$.notes"),
    @MongoFieldAlias(name = "PUNISHMENT_EVIDENCE", path = "punishments.$.evidence"),
    @MongoFieldAlias(name = "PUNISHMENT_STARTED", path = "punishments.$.started"),
    @MongoFieldAlias(name = "PUNISHMENT_ATTACHED_TICKET_IDS", path = "punishments.$.attachedTicketIds"),
    @MongoFieldAlias(name = "PUNISHMENT_DATA", path = "punishments.$.data")
})
public class Player {
    @Id
    @Field(targetType = FieldType.STRING)
    private String id;

    @Field(name = "minecraftUuid", targetType = FieldType.STRING)
    private UUID minecraftUuid;

    @Field(name = "usernames")
    @Builder.Default
    private List<UsernameEntry> usernames = new ArrayList<>();

    @Field(name = "notes")
    @Builder.Default
    private List<NoteEntry> notes = new ArrayList<>();

    @Field(name = "ipAddresses")
    @Builder.Default
    private List<IPEntry> ipAddresses = new ArrayList<>();

    @Field(name = "punishments")
    @Builder.Default
    private List<Punishment> punishments = new ArrayList<>();

    @Field(name = "data")
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();
}
