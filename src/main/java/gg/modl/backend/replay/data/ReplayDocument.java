package gg.modl.backend.replay.data;

import java.util.Date;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@NoArgsConstructor
@Document
public class ReplayDocument {
    @Id
    private String id;
    private String mcVersion;
    private String targetUuid;
    private String targetName;
    private long fileSize;
    private String storageKey;
    private String status;
    private Date createdAt;
    private List<ReplayLabel> labels;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED = "FAILED";
}
