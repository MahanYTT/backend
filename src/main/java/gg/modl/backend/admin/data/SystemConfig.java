package gg.modl.backend.admin.data;

import gg.modl.backend.Constants;
import gg.modl.backend.database.mongo.codegen.GenerateMongoFields;
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
@Document(collection = "system_config")
@GenerateMongoFields
public class SystemConfig {
    @Id
    private String id;
    @Field("configId")
    private String configId = "main_config";

    @Field("general")
    private GeneralConfig general = new GeneralConfig();
    @Field("logging")
    private LoggingConfig logging = new LoggingConfig();
    @Field("security")
    private SecurityConfig security = new SecurityConfig();
    @Field("notifications")
    private NotificationsConfig notifications = new NotificationsConfig();
    @Field("performance")
    private PerformanceConfig performance = new PerformanceConfig();
    @Field("features")
    private FeaturesConfig features = new FeaturesConfig();

    @Field("createdAt")
    private Date createdAt = new Date();
    @Field("updatedAt")
    private Date updatedAt = new Date();

    @Getter
    @Setter
    public static class GeneralConfig {
        @Field("systemName")
        private String systemName = "modl Admin";
        @Field("adminEmail")
        private String adminEmail = Constants.Email.ADMIN;
        @Field("timezone")
        private String timezone = "UTC";
        @Field("defaultLanguage")
        private String defaultLanguage = "en";
        @Field("maintenanceMode")
        private boolean maintenanceMode = false;
        @Field("maintenanceMessage")
        private String maintenanceMessage = "System under maintenance. Please check back later.";
    }

    @Getter
    @Setter
    public static class LoggingConfig {
        @Field("pm2LoggingEnabled")
        private boolean pm2LoggingEnabled = true;
        @Field("logRetentionDays")
        private int logRetentionDays = 30;
        @Field("maxLogSizePerDay")
        private int maxLogSizePerDay = 1000000;
    }

    @Getter
    @Setter
    public static class SecurityConfig {
        @Field("sessionTimeout")
        private int sessionTimeout = 60;
        @Field("maxLoginAttempts")
        private int maxLoginAttempts = 5;
        @Field("lockoutDuration")
        private int lockoutDuration = 15;
        @Field("requireTwoFactor")
        private boolean requireTwoFactor = false;
        @Field("passwordMinLength")
        private int passwordMinLength = 8;
        @Field("passwordRequireSpecial")
        private boolean passwordRequireSpecial = false;
        @Field("ipWhitelist")
        private List<String> ipWhitelist = new ArrayList<>();
        @Field("corsOrigins")
        private List<String> corsOrigins = new ArrayList<>(List.of(Constants.Domain.HTTPS_ADMIN));
    }

    @Getter
    @Setter
    public static class NotificationsConfig {
        @Field("emailNotifications")
        private boolean emailNotifications = true;
        @Field("criticalAlerts")
        private boolean criticalAlerts = true;
        @Field("weeklyReports")
        private boolean weeklyReports = true;
        @Field("maintenanceAlerts")
        private boolean maintenanceAlerts = true;
        @Field("slackWebhook")
        private String slackWebhook = "";
        @Field("discordWebhook")
        private String discordWebhook = "";
    }

    @Getter
    @Setter
    public static class PerformanceConfig {
        @Field("cacheTtl")
        private int cacheTtl = 300;
        @Field("rateLimitRequests")
        private int rateLimitRequests = 100;
        @Field("rateLimitWindow")
        private int rateLimitWindow = 60;
        @Field("databaseConnectionPool")
        private int databaseConnectionPool = 10;
        @Field("enableCompression")
        private boolean enableCompression = true;
        @Field("enableCaching")
        private boolean enableCaching = true;
    }

    @Getter
    @Setter
    public static class FeaturesConfig {
        @Field("analyticsEnabled")
        private boolean analyticsEnabled = true;
        @Field("auditLoggingEnabled")
        private boolean auditLoggingEnabled = true;
        @Field("apiAccessEnabled")
        private boolean apiAccessEnabled = true;
        @Field("bulkOperationsEnabled")
        private boolean bulkOperationsEnabled = true;
        @Field("advancedFiltering")
        private boolean advancedFiltering = true;
        @Field("realTimeUpdates")
        private boolean realTimeUpdates = true;
    }
}
