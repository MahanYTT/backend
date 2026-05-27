package gg.modl.backend.server.data;

import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
import gg.modl.backend.server.ServerField;
import java.util.Date;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.annotation.Id;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Document(collection = CollectionName.MODL_SERVERS)
@Getter
@Setter
@RequiredArgsConstructor
@GenerateMongoFields
public class Server {
    @NotNull
    @Field(name = ServerField.SERVER_NAME, targetType = FieldType.STRING)
    private final String serverName;

    @NotNull
    @Field(name = ServerField.SUBDOMAIN, targetType = FieldType.STRING)
    private final String customDomain;

    @Nullable
    @Field(name = "databaseName", targetType = FieldType.STRING)
    private final String databaseName;

    @Id
    @Field(targetType = FieldType.OBJECT_ID)
    private String id;

    @NotNull
    @Field(name = ServerField.ADMIN_EMAIL, targetType = FieldType.STRING)
    private String adminEmail;

    @NotNull
    @Field(name = "emailVerified", targetType = FieldType.BOOLEAN)
    private Boolean emailVerified;

    @Nullable
    @Field(name = "emailVerificationToken", targetType = FieldType.STRING)
    private String emailVerificationToken;

    @Nullable
    @Field(name = "cleanupClaimId", targetType = FieldType.STRING)
    private String cleanupClaimId;

    @Nullable
    @Field(name = "cleanupClaimedAt", targetType = FieldType.DATE_TIME)
    private Date cleanupClaimedAt;

    @Nullable
    @Field(name = "provisioningStatus", targetType = FieldType.STRING)
    private ProvisioningStatus provisioningStatus;

    @Nullable
    @Field(name = "provisioningNotes", targetType = FieldType.STRING)
    private String provisioningNotes;

    @Nullable
    @Field(name = "provisioningSignInToken", targetType = FieldType.STRING)
    private String provisioningSignInToken;

    @Nullable
    @Field(name = "provisioningSignInTokenExpiresAt", targetType = FieldType.DATE_TIME)
    private Date provisioningSignInTokenExpiresAt;

    @NotNull
    @Field(name = "plan", targetType = FieldType.STRING)
    private ServerPlan plan;

    @Nullable
    @Field(name = "subscriptionStatus", targetType = FieldType.STRING)
    private SubscriptionStatus subscriptionStatus;

    @Nullable
    @Field(name = "currentPeriodStart", targetType = FieldType.DATE_TIME)
    private Date currentPeriodStart;

    @Nullable
    @Field(name = "currentPeriodEnd", targetType = FieldType.DATE_TIME)
    private Date currentPeriodEnd;

    @Nullable
    @Field(name = "stripeCustomerId", targetType = FieldType.STRING)
    private String stripeCustomerId;

    @Nullable
    @Field(name = "stripeSubscriptionId", targetType = FieldType.STRING)
    private String stripeSubscriptionId;

    @Nullable
    @Field(name = "cdnUsageCurrentPeriod", targetType = FieldType.DOUBLE)
    private Double cdnUsageCurrentPeriod; // GB used in current billing period

    @Nullable
    @Field(name = "aiRequestsCurrentPeriod", targetType = FieldType.INT64)
    private Long aiRequestsCurrentPeriod; // AI requests used in current billing period

    @Nullable
    @Field(name = "usageBillingEnabled", targetType = FieldType.BOOLEAN)
    private Boolean usageBillingEnabled;

    @Nullable
    @Field(name = "usageBillingUpdatedAt", targetType = FieldType.DATE_TIME)
    private Date usageBillingUpdatedAt;

    @Nullable
    @Field(name = "usageResetAt", targetType = FieldType.DATE_TIME)
    private Date usageResetAt;

    @Nullable
    @Field(name = "storageUsedBytes", targetType = FieldType.INT64)
    private Long storageUsedBytes;

    @Nullable
    @Field(name = "maxStorageLimitBytes", targetType = FieldType.INT64)
    private Long maxStorageLimitBytes;

    @Nullable
    @Field(name = "maxAiOverageRequests", targetType = FieldType.INT64)
    private Long maxAiOverageRequests;

    @Nullable
    @Field(name = "migrationFileSizeLimit", targetType = FieldType.INT64)
    private Long migrationFileSizeLimit;

    @Nullable
    @Field(name = ServerField.CUSTOM_DOMAIN, targetType = FieldType.STRING)
    private String customDomainOverride;

    @Nullable
    @Field(name = ServerField.CUSTOM_DOMAIN_STATUS, targetType = FieldType.STRING)
    private CustomDomainStatus customDomainStatus;

    @Nullable
    @Field(name = "customDomainLastChecked", targetType = FieldType.DATE_TIME)
    private Date customDomainLastChecked;

    @Nullable
    @Field(name = "customDomainError", targetType = FieldType.STRING)
    private String customDomainError;

    @Nullable
    @Field(name = "customDomainCloudflareId", targetType = FieldType.STRING)
    private String customDomainCloudflareId;

    @Nullable
    @Field(name = ServerField.CUSTOM_DOMAIN_GRANDFATHERED, targetType = FieldType.BOOLEAN)
    private Boolean customDomainGrandfathered;

    @Nullable
    @Field(name = "cliSetupToken", targetType = FieldType.STRING)
    private String cliSetupToken;

    @Nullable
    @Field(name = "apiKey", targetType = FieldType.STRING)
    private String apiKey;

    @Nullable
    @Field(name = "onlinePlayerCount", targetType = FieldType.INT64)
    private Long onlinePlayerCount;

    @Nullable
    @Field(name = "userCount", targetType = FieldType.INT64)
    private Long userCount;

    @Nullable
    @Field(name = "ticketCount", targetType = FieldType.INT64)
    private Long ticketCount;

    @Nullable
    @Field(name = "lastStatsUpdatedAt", targetType = FieldType.DATE_TIME)
    private Date lastStatsUpdatedAt;

    @Nullable
    @Field(name = "lastActivityAt", targetType = FieldType.DATE_TIME)
    private Date lastActivityAt;

    @Nullable
    @Field(name = "createdAt", targetType = FieldType.DATE_TIME)
    private Date createdAt;

    @Nullable
    @Field(name = "updatedAt", targetType = FieldType.DATE_TIME)
    private Date updatedAt;

    @Nullable
    @Field(name = "staffPermissionsUpdatedAt", targetType = FieldType.DATE_TIME)
    private Date staffPermissionsUpdatedAt;

    @Nullable
    @Field(name = "punishmentTypesUpdatedAt", targetType = FieldType.DATE_TIME)
    private Date punishmentTypesUpdatedAt;
}
