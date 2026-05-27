package gg.modl.backend.ticket.data;

import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import gg.modl.backend.database.mongo.codegen.MongoFieldAlias;
import gg.modl.backend.database.mongo.codegen.MongoFieldAliases;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document
@GenerateMongoFields
@MongoFieldAliases({
    @MongoFieldAlias(name = "REPLY_NAME", path = "replies.name"),
    @MongoFieldAlias(name = "REPLY_CONTENT", path = "replies.content"),
    @MongoFieldAlias(name = "REPLY_CREATED", path = "replies.created"),
    @MongoFieldAlias(name = "REPLY_STAFF", path = "replies.staff")
})
public class Ticket {
    @Id
    @NotNull
    private String id;

    @Field(targetType = FieldType.STRING)
    private TicketCategory type;

    private String subject;

    @Field(targetType = FieldType.STRING)
    private TicketStatus status;

    private String creatorUuid;
    private String creatorName;
    private String creatorAvatar;

    private String reportedPlayer;
    private String reportedPlayerUuid;

    @Builder.Default
    @NotNull
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    @NotNull
    private List<TicketReply> replies = new ArrayList<>();

    @Builder.Default
    @NotNull
    private List<TicketNote> notes = new ArrayList<>();

    @Nullable
    private List<ChatMessage> chatMessages;

    @Nullable
    private Map<String, Object> formData;

    @Nullable
    private Map<String, Object> data;

    private boolean locked;

    @Builder.Default
    @Field(targetType = FieldType.STRING)
    private TicketPriority priority = TicketPriority.NORMAL;

    @Builder.Default
    @NotNull
    private List<String> assignedTo = new ArrayList<>();

    private Date created;
    private Date updatedAt;

    @Nullable
    @Field(targetType = FieldType.STRING)
    private AppealWorkflowStatus appealWorkflowStatus;

    @Nullable
    private AIAnalysisResult aiAnalysis;

    @Nullable
    private String replayUrl;

    private boolean emailAuthEnabled;
    private boolean hidden;

    public void applyLifecycleStatus(TicketStatus status) {
        this.status = status;
        this.locked = status != null && status.isTerminal();
    }

    public List<TicketReply> ensureReplies() {
        if (this.replies == null) {
            this.replies = new ArrayList<>();
        }
        return Collections.unmodifiableList(this.replies);
    }

    public List<TicketNote> ensureNotes() {
        if (this.notes == null) {
            this.notes = new ArrayList<>();
        }
        return Collections.unmodifiableList(this.notes);
    }

    public void addReply(@NotNull TicketReply reply) {
        if (this.replies == null) {
            this.replies = new ArrayList<>();
        }
        this.replies.add(reply);
    }

    public void addNote(@NotNull TicketNote note) {
        if (this.notes == null) {
            this.notes = new ArrayList<>();
        }
        this.notes.add(note);
    }

    @Getter
    @Setter
    @AllArgsConstructor
    public static class ChatMessage {
        @NotNull
        private String content;

        private Date timestamp;
    }
}
