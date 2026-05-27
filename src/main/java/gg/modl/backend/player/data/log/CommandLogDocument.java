package gg.modl.backend.player.data.log;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = CollectionName.COMMAND_LOGS)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateMongoFields(className = "CommandLogFields")
public class CommandLogDocument {
    @Id
    private String id;

    @Field("uuid")
    private String uuid;

    @Field("username")
    private String username;

    @Field("command")
    private String command;

    @Field("timestamp")
    private long timestamp;

    @Field("server")
    private String server;
}
