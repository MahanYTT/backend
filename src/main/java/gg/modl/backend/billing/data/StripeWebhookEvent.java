package gg.modl.backend.billing.data;

import gg.modl.backend.database.CollectionName;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = CollectionName.STRIPE_WEBHOOK_EVENTS)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StripeWebhookEvent {
    @Id
    private String id;

    @Field("type")
    private String type;

    @Field("processedAt")
    private Date processedAt;

    @Field("processingAt")
    private Date processingAt;

    @Field("failedAt")
    private Date failedAt;

    @Field("status")
    private String status;

    @Field("error")
    private String error;

    public StripeWebhookEvent(String id, String type, Date processingAt) {
        this.id = id;
        this.type = type;
        this.processingAt = processingAt;
        this.status = "processing";
    }
}
