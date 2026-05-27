package gg.modl.backend.replay.data;

import java.util.Date;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.Binary;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@NoArgsConstructor
@Document
public class TrainingSegmentDocument {
    @Id
    private String id;
    private String replayId;
    private String serverName;
    private String serverDatabaseName;
    private String playerUuid;
    private String playerName;
    private String verdict;
    private String cheatType;
    private int confidence;
    private String notes;
    private long startMs;
    private long endMs;
    private String mcVersion;
    private Binary segmentBinary;
    private Date createdAt;
}
