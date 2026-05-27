package gg.modl.backend.server.service;

import gg.modl.backend.database.MongoIndexBootstrapService;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.database.mongo.repository.HomepageCardMongoRepository;
import gg.modl.backend.database.mongo.repository.KnowledgebaseCategoryMongoRepository;
import gg.modl.backend.database.mongo.repository.SettingsMongoRepository;
import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.Settings;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServerProvisioningService {
    private static final String AI_CHAT_ABUSE_CONFIG_ID = "6";
    private static final String AI_ANTI_SOCIAL_CONFIG_ID = "7";

    private final TenantMongoAccess tenantMongoAccess;
    private final MongoIndexBootstrapService mongoIndexBootstrapService;
    private final SettingsMongoRepository settingsRepository;
    private final KnowledgebaseCategoryMongoRepository knowledgebaseCategoryRepository;
    private final HomepageCardMongoRepository homepageCardRepository;
    private final RoleService roleService;

    public void provision(Server server) {
        try {
            mongoIndexBootstrapService.createTenantIndexes(tenantMongoAccess.forServer(server));
            seedAIModerationSettings(server);
            seedTicketForms(server);
            seedQuickResponses(server);
            seedGeneralSettings(server);
            seedTicketLabelSettings(server);
            List<KnowledgebaseCategory> categories = seedKnowledgebaseCategories(server);
            seedHomepageCards(server, categories);
            roleService.createDefaultRoles(server);
        } catch (Exception e) {
            log.error("[Provisioning] Error provisioning server: {}", server.getCustomDomain(), e);
        }
    }

    private void seedAIModerationSettings(Server server) {
        if (settingsExist(server, "aiModerationSettings")) {
            return;
        }

        Map<String, Object> chatAbuseConfig = new LinkedHashMap<>();
        chatAbuseConfig.put("id", AI_CHAT_ABUSE_CONFIG_ID);
        chatAbuseConfig.put("name", "Chat Abuse");
        chatAbuseConfig.put("aiDescription",
            "Chat abuse is the act of spamming, excessive profanity, abusive language, inappropriate topics or jokes, and misleading information");
        chatAbuseConfig.put("enabled", true);

        Map<String, Object> antiSocialConfig = new LinkedHashMap<>();
        antiSocialConfig.put("id", AI_ANTI_SOCIAL_CONFIG_ID);
        antiSocialConfig.put("name", "Anti Social");
        antiSocialConfig.put("aiDescription",
            "Anti social is the act of harassing, threatening, black-mailing, or otherwise abusing another player or group of players. This includes bigotry and other forms of discrimination against protected classes.");
        antiSocialConfig.put("enabled", true);

        Map<String, Object> aiPunishmentConfigs = new LinkedHashMap<>();
        aiPunishmentConfigs.put(AI_CHAT_ABUSE_CONFIG_ID, chatAbuseConfig);
        aiPunishmentConfigs.put(AI_ANTI_SOCIAL_CONFIG_ID, antiSocialConfig);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enableAIReview", false);
        data.put("enableAutomatedActions", false);
        data.put("aiPunishmentConfigs", aiPunishmentConfigs);

        settingsRepository.saveEntity(server, newSettingsDocument("aiModerationSettings", data));
    }

    private boolean settingsExist(Server server, String type) {
        return settingsRepository.existsByType(server, type);
    }

    private Settings newSettingsDocument(String type, Map<String, Object> data) {
        return new Settings(null, type, data, 0L, new Date());
    }

    private void seedTicketForms(Server server) {
        if (settingsExist(server, "ticketForms")) {
            return;
        }

        // Bug report form
        Map<String, Object> bugForm = new LinkedHashMap<>();
        bugForm.put("fields", List.of(
            formField("1753243804677", "textarea", "Bug Description", "Describe the bug in full detail", true, 3, "1753243782799"),
            formField("1753243846548", "textarea", "Environment", "Game/server, client version, and any other relevant conditions", true, 3, "1753243782799"),
            formField("1753243865490", "textarea", "Steps to reproduce", "Detailed description on how we can reproduce the bug", true, 2, "1753243782799"),
            formField("1753243883567", "textarea", "Any other information?", null, false, 3, "1753243782799"),
            formField("1753243946458", "file_upload", "Attachments", "Upload relevant attachments to help us squash this bug.", false, 4, "1753243782799")
        ));
        bugForm.put("sections", List.of(
            formSection("1753243782799", "General", 0, false)
        ));

        // Support form
        Map<String, Object> supportForm = new LinkedHashMap<>();
        supportForm.put("fields", List.of(
            formField("1753243961223", "textarea", "Description", "How can we assist you?", true, 0, "1753243900648"),
            formField("1753243997358", "file_upload", "Attachments", "Upload any relevant attachments.", false, 1, "1753243900648")
        ));
        supportForm.put("sections", List.of(
            formSection("1753243900648", "General", 0, false)
        ));

        // Application form
        Map<String, Object> applicationForm = buildApplicationForm();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bug", bugForm);
        data.put("support", supportForm);
        data.put("application", applicationForm);

        settingsRepository.saveEntity(server, newSettingsDocument("ticketForms", data));
    }

    private Map<String, Object> buildApplicationForm() {
        List<Map<String, Object>> fields = new ArrayList<>();

        fields.add(formField("1753244313811", "text", "First Name", null, true, 0, "1753244011186"));
        fields.add(formField("1753244038340", "text", "Discord username", "Please use the new username format, starting with an @.", true, 1, "1753244011186"));
        fields.add(formField("1753244070995", "text", "Age", null, true, 2, "1753244011186"));
        fields.add(formField("1753244166086", "text", "Region & Timezone", "Ex: NA, Eastern Time", true, 3, "1753244011186"));
        fields.add(
            formField("1753244525756", "text", "What languages can you speak?", "If you speak more than one, please list your level of fluency in each.", true,
                4, "1753244011186"));
        fields.add(
            formField("1753244114967", "checkbox", "Do you have access to both a working microphone and recording software?", null, true, 5, "1753244011186"));

        Map<String, Object> positionField = new LinkedHashMap<>();
        positionField.put("id", "1753244244863");
        positionField.put("type", "dropdown");
        positionField.put("label", "Position");
        positionField.put("description", "What position are you applying for?");
        positionField.put("required", true);
        positionField.put("options", List.of("Moderator", "Builder", "Developer", "Media"));
        positionField.put("order", 6);
        positionField.put("sectionId", "1753244011186");
        positionField.put("optionSectionMapping", Map.of(
            "Moderator", "1753244183109",
            "Builder", "1753244277605",
            "Developer", "1753244282540",
            "Media", "1753244286527"
        ));
        fields.add(positionField);

        fields.add(formField("1753244506417", "textarea", "Have you ever been banned or muted on this server? If yes, what have you learned moving forward?",
            "If so, please explain each occurrence.", true, 0, "1753244183109"));
        fields.add(formField("1753244551193", "textarea", "Describe your moderation background and previous experience.",
            "The more detail the better. This doesn't have to be limited to Minecraft servers, as we welcome any previous experience in moderating Discord servers or even other game communities.  Please provide references and proof for your more notable experiences.",
            true, 1, "1753244183109"));
        fields.add(formField("1753244585381", "textarea", "Why do you want to become a moderator on this server?",
            "Again, the more detail on this question the better. Providing us with as much detail as possible will help us understand your motivation and will to become a moderator!",
            true, 9, "1753244183109"));
        fields.add(formField("1753244603377", "text", "How much time do you see yourself committing to the server?", null, true, 10, "1753244183109"));
        fields.add(formField("1753244687326", "textarea",
            "You are a Moderator with the ability to mute and ban. You are playing on the server with a friend and come across a player who you think is hacking. They kill your friend, but then you kill them. What do you do in this situation?",
            null, true, 11, "1753244183109"));
        fields.add(formField("1753244762984", "textarea",
            "You are a Moderator with the ability to mute and ban. You are spectating a player who you believe is hacking, but multiple chat reports come in about a player in another gamemode who is being violently disruptive in chat. Somehow, you are the only moderator online. How do you handle the two situations?",
            null, true, 12, "1753244183109"));
        fields.add(formField("1753244861431", "textarea",
            "You are a Moderator with the ability to mute and ban. You see 5+ reports come in accusing the same player of breaking the chat rules. You join the server where the situation is taking place and open the accused player's recent chat history. You see that they were being rude, but haven't actually broken a rule. When you decide that they are not guilty, the same group reports the player again, and sends you multiple private messages calling you a bad moderator for not muting the player. What's the first step in dealing with this situation? Explain how this step will move towards resolving the conflict.",
            null, true, 13, "1753244183109"));
        fields.add(formField("1753244931272", "textarea",
            "You are the newest Moderator on the team. While you are spectating a game, you witness a Sr. Moderator mining suspiciously. In a matter of minutes, you get enough evidence that suggests that the Sr. Moderator may likely be x-raying. Suddenly, they head to the surface and do nothing suspicious for the rest of your time spectating them. How do you proceed?",
            null, true, 14, "1753244183109"));
        fields.add(formField("1753245023983", "textarea",
            "You are a Moderator with the ability to mute and ban. You notice a well-known streamer/YouTuber closely affiliated with the server is nicked. They message a player words encouraging suicide under their disguised alias. What steps do you take to resolve the situation?",
            null, true, 15, "1753244183109"));

        fields.add(formField("1753245081481", "textarea", "Do you have experience building for other servers?", null, true, 16, "1753244277605"));
        fields.add(formField("1753245137086", "textarea", "Please provide proof of previous work in link form here (Imgur, YouTube, etc)", null, true, 17,
            "1753244277605"));
        fields.add(formField("1753245154307", "textarea", "Anything else you would like to say?", null, false, 23, "1753244277605"));

        fields.add(formField("1753245191475", "textarea", "Why do you want to be a developer on this server?", null, true, 0, "1753244282540"));
        fields.add(formField("1753245262717", "textarea", "Do you have experience developing for other servers?", null, true, 1, "1753244282540"));
        fields.add(formField("1753245280773", "text", "Please provide proof of previous work in the form of a GitHub link", null, true, 2, "1753244282540"));
        fields.add(formField("1753245291714", "textarea", "Anything else you would like to say?", null, false, 3, "1753244282540"));

        fields.add(formField("1753245348514", "text", "Have you ever been banned or muted on this server? If yes, what have you learned moving forward?",
            "If so, please explain each occurrence.", true, 23, "1753244286527"));
        fields.add(formField("1753245358313", "text", "A link to your YouTube and/or Stream Channel", null, true, 24, "1753244286527"));
        fields.add(formField("1753245471763", "checkbox",
            "We will email the contact email listed on the channel for proof of ownership, please verify it is accurate and actively monitored.", null, true,
            25, "1753244286527"));
        fields.add(formField("1753245511672", "textarea", "Anything else you would like to say?", null, false, 26, "1753244286527"));

        List<Map<String, Object>> sections = List.of(
            formSection("1753244011186", "General", 0, false),
            formSection("1753244183109", "Moderator", 1, true),
            formSection("1753244277605", "Builder", 2, true),
            formSection("1753244282540", "Developer", 3, true),
            formSection("1753244286527", "Media", 4, true)
        );

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("fields", fields);
        form.put("sections", sections);
        return form;
    }

    private Map<String, Object> formField(String id, String type, String label, String description, boolean required, int order, String sectionId) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", id);
        field.put("type", type);
        field.put("label", label);
        if (description != null) {
            field.put("description", description);
        }
        field.put("required", required);
        field.put("order", order);
        field.put("sectionId", sectionId);
        return field;
    }

    private Map<String, Object> formSection(String id, String title, int order, boolean hideByDefault) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("id", id);
        section.put("title", title);
        section.put("order", order);
        section.put("hideByDefault", hideByDefault);
        return section;
    }

    private void seedQuickResponses(Server server) {
        if (settingsExist(server, "quickResponses")) {
            return;
        }

        List<Map<String, Object>> categories = new ArrayList<>();

        // Chat Report Actions
        categories.add(quickResponseCategory("chat_report_actions", "Chat Report Actions", List.of("chat_report"), 1, List.of(
            quickResponseAction("accept_report", "Accept Report",
                "Thank you for creating this report. After careful review, we have accepted this and the reported player will be receiving a punishment.", 1,
                true, true, null),
            quickResponseAction("reject_insufficient_chat", "More Evidence",
                "Thank you for submitting this chat report. After reviewing the evidence provided, we need additional evidence to proceed with action.", 2,
                false, false, null),
            quickResponseAction("reject_no_violation_chat", "Reject - No Violation",
                "Thank you for submitting this chat report. After reviewing the evidence provided, we have determined that this does not violate our community guidelines.",
                3, true, false, null)
        )));

        // Player Report Actions
        categories.add(quickResponseCategory("player_report_actions", "Player Report Actions", List.of("player_report"), 2, List.of(
            quickResponseAction("accept_report", "Accept Report",
                "Thank you for creating this report. After careful review, we have accepted this and the reported player will be receiving a punishment.", 1,
                true, true, null),
            quickResponseAction("reject_insufficient_player", "More Evidence",
                "Thank you for submitting this player report. After reviewing the evidence provided, we need additional evidence to proceed with action.", 2,
                false, false, null),
            quickResponseAction("reject_no_violation_player", "Reject - No Violation",
                "Thank you for submitting this player report. After reviewing the evidence provided, we have determined that this does not violate our community guidelines.",
                3, true, false, null)
        )));

        // Appeal Actions
        categories.add(quickResponseCategory("appeal_actions", "Appeal Actions", List.of("appeal"), 3, List.of(
            quickResponseAction("pardon_full", "Pardon - Full",
                "After reviewing your appeal, we have decided to remove the punishment completely. We apologize for any inconvenience.", 1, true, false,
                "pardon"),
            quickResponseAction("reduce_punishment", "Reduce Punishment",
                "We have reviewed your appeal and decided to reduce the duration of your punishment. Please check your punishment details for the updated duration.",
                2, true, false, "reduce"),
            quickResponseAction("reject_upheld", "Reject - Upheld",
                "After careful consideration of your appeal, we have decided to uphold the original punishment.", 3, true, false, "reject"),
            quickResponseAction("need_more_info_appeal", "Need More Information",
                "We need additional information to process your appeal. Please provide more details about your situation.", 4, false, false, null)
        )));

        // Staff Application Actions
        categories.add(quickResponseCategory("application_actions", "Staff Application Actions", List.of("application"), 4, List.of(
            quickResponseAction("accept_builder", "Accept - Builder",
                "Congratulations! Your Builder application has been accepted. Welcome to the Builder team! You will receive further instructions and permissions shortly.",
                1, true, false, null),
            quickResponseAction("accept_helper", "Accept - Helper",
                "Congratulations! Your Helper application has been accepted. Welcome to the Helper team! You will receive further instructions and permissions shortly.",
                2, true, false, null),
            quickResponseAction("accept_developer", "Accept - Developer",
                "Congratulations! Your Developer application has been accepted. Welcome to the Developer team! You will receive further instructions and permissions shortly.",
                3, true, false, null),
            quickResponseAction("reject_application", "Reject Application",
                "Thank you for your interest in joining our team. Unfortunately, we have decided not to move forward with your application at this time. You may reapply in the future.",
                4, true, false, null),
            quickResponseAction("pending_review", "Pending Review",
                "Thank you for your application. We are currently reviewing it and will get back to you soon.", 5, false, false, null),
            quickResponseAction("interview_scheduled", "Interview Scheduled",
                "Your application has progressed to the interview stage. Please check your email for interview details.", 6, false, false, null),
            quickResponseAction("need_more_info_app", "Need More Information",
                "We need additional information about your application. Please provide more details about your experience and qualifications.", 7, false, false,
                null)
        )));

        // Bug Report Actions
        categories.add(quickResponseCategory("bug_actions", "Bug Report Actions", List.of("bug"), 5, List.of(
            quickResponseAction("completed", "Fixed", "Thank you for reporting this bug. We have fixed the issue and it will be included in our next update.",
                1, true, false, null),
            quickResponseAction("investigating", "Investigating",
                "Thank you for this bug report. We are currently investigating the issue and will provide updates as they become available.", 2, false, false,
                null),
            quickResponseAction("need_more_info", "Need More Info",
                "Thank you for this bug report. We need additional information to investigate this issue. Please provide more details about how to reproduce this bug.",
                3, false, false, null),
            quickResponseAction("duplicate", "Duplicate",
                "This bug has been identified as a duplicate of an existing issue. We appreciate your report and are working on a fix.", 4, true, false, null),
            quickResponseAction("cannot_reproduce", "Cannot Reproduce",
                "We were unable to reproduce this issue. If you continue to experience this problem, please provide additional details.", 5, true, false, null)
        )));

        // Support Actions
        categories.add(quickResponseCategory("support_actions", "Support Actions", List.of("support"), 6, List.of(
            quickResponseAction("resolved", "Resolved",
                "Your support request has been resolved. If you need further assistance, please feel free to create a new ticket.", 1, true, false, null),
            quickResponseAction("escalated", "Escalated",
                "Your support request has been escalated to our specialized team. They will contact you with additional information.", 2, false, false, null),
            quickResponseAction("need_info_support", "Need More Info",
                "We need additional information to assist you with your request. Please provide more details about your issue.", 3, false, false, null)
        )));

        // General Actions
        categories.add(
            quickResponseCategory("general_actions", "General Actions", List.of("player_report", "chat_report", "bug", "appeal", "support", "application"), 7,
                List.of(
                    quickResponseAction("acknowledge", "Acknowledge", "Thank you for your message. We have received your ticket and will review it shortly.", 1,
                        false, false, null),
                    quickResponseAction("follow_up", "Follow Up",
                        "We are following up on your ticket. Please let us know if you have any additional information or questions.", 2, false, false, null)
                )));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categories", categories);

        settingsRepository.saveEntity(server, newSettingsDocument("quickResponses", data));
    }

    private Map<String, Object> quickResponseCategory(String id, String name, List<String> ticketTypes, int order, List<Map<String, Object>> actions) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("id", id);
        category.put("name", name);
        category.put("ticketTypes", ticketTypes);
        category.put("order", order);
        category.put("actions", actions);
        return category;
    }

    private Map<String, Object> quickResponseAction(String id, String name, String message, int order, boolean closeTicket, boolean showPunishment, String appealAction) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("id", id);
        action.put("name", name);
        action.put("message", message);
        action.put("order", order);
        action.put("closeTicket", closeTicket);
        if (showPunishment) {
            action.put("showPunishment", true);
        }
        if (appealAction != null) {
            action.put("appealAction", appealAction);
        }
        return action;
    }

    private void seedGeneralSettings(Server server) {
        if (settingsExist(server, "general")) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverDisplayName", "");
        data.put("discordWebhookUrl", "");
        data.put("homepageIconUrl", "");
        data.put("panelIconUrl", "");

        settingsRepository.saveEntity(server, newSettingsDocument("general", data));
    }

    private void seedTicketLabelSettings(Server server) {
        if (settingsExist(server, "ticketLabels")) {
            return;
        }

        List<Map<String, Object>> labels = List.of(
            labelMap("high priority", "#e74c3c", "High priority tickets"),
            labelMap("needs admin review", "#f39c12", "Tickets that need review"),
            labelMap("in progress", "#2ecc71", "Tickets being worked on"),
            labelMap("won't fix", "#6b7280", "Issues that won't be fixed"),
            labelMap("duplicate", "#6b7280", "Duplicate tickets")
        );

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);

        settingsRepository.saveEntity(server, newSettingsDocument("ticketLabels", data));
    }

    private Map<String, Object> labelMap(String name, String color, String description) {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("id", UUID.randomUUID().toString());
        label.put("name", name);
        label.put("color", color);
        label.put("description", description);
        return label;
    }

    private List<KnowledgebaseCategory> seedKnowledgebaseCategories(Server server) {
        if (knowledgebaseCategoryRepository.hasAny(server)) {
            return List.of();
        }

        Date now = new Date();
        List<KnowledgebaseCategory> categories = List.of(
            KnowledgebaseCategory.builder()
                .name("Rules & Policies")
                .slug("rules-policies")
                .description("Server rules, community guidelines, and policies")
                .ordinal(0)
                .isVisible(true)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            KnowledgebaseCategory.builder()
                .name("Guides & Troubleshooting")
                .slug("guides-troubleshooting")
                .description("How-to guides and troubleshooting help")
                .ordinal(1)
                .isVisible(true)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            KnowledgebaseCategory.builder()
                .name("News & Updates")
                .slug("news-updates")
                .description("Latest announcements, updates, and news")
                .ordinal(2)
                .isVisible(true)
                .createdAt(now)
                .updatedAt(now)
                .build()
        );

        for (KnowledgebaseCategory category : categories) {
            knowledgebaseCategoryRepository.saveEntity(server, category);
        }
        return categories;
    }

    private void seedHomepageCards(Server server, List<KnowledgebaseCategory> categories) {
        if (homepageCardRepository.hasAny(server)) {
            return;
        }

        String rulesCategoryId = categories.stream()
            .filter(c -> "rules-policies".equals(c.getSlug()))
            .findFirst().map(KnowledgebaseCategory::getId).orElse(null);
        String guidesCategoryId = categories.stream()
            .filter(c -> "guides-troubleshooting".equals(c.getSlug()))
            .findFirst().map(KnowledgebaseCategory::getId).orElse(null);
        String newsCategoryId = categories.stream()
            .filter(c -> "news-updates".equals(c.getSlug()))
            .findFirst().map(KnowledgebaseCategory::getId).orElse(null);

        Date now = new Date();

        List<HomepageCard> cards = List.of(
            HomepageCard.builder()
                .title("Appeal Punishment")
                .description("Submit an appeal if you believe you were unfairly banned or punished")
                .icon("Scale")
                .iconColor("#f59e0b")
                .actionType("url")
                .actionUrl("/appeal")
                .actionButtonText("Submit Appeal")
                .isEnabled(true)
                .ordinal(0)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .title("Apply for Staff")
                .description("Join our staff team and help manage the community")
                .icon("UserPlus")
                .iconColor("#3b82f6")
                .actionType("url")
                .actionUrl("/submit-ticket/apply")
                .actionButtonText("Apply Now")
                .isEnabled(true)
                .ordinal(1)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .title("Contact Us")
                .description("Get help from our support team for any issues")
                .icon("MessageCircle")
                .iconColor("#10b981")
                .actionType("url")
                .actionUrl("/submit-ticket/support")
                .actionButtonText("Contact Support")
                .isEnabled(true)
                .ordinal(2)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .title("Rules & Policies")
                .description("Browse server rules, community guidelines, and policies")
                .icon("BookOpen")
                .iconColor("#8b5cf6")
                .actionType("category_dropdown")
                .categoryId(rulesCategoryId)
                .isEnabled(true)
                .ordinal(3)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .title("Guides & Troubleshooting")
                .description("Find helpful guides and troubleshooting resources")
                .icon("HelpCircle")
                .iconColor("#f97316")
                .actionType("category_dropdown")
                .categoryId(guidesCategoryId)
                .isEnabled(true)
                .ordinal(4)
                .createdAt(now)
                .updatedAt(now)
                .build(),
            HomepageCard.builder()
                .title("News & Updates")
                .description("Stay up to date with the latest announcements and changes")
                .icon("Newspaper")
                .iconColor("#6366f1")
                .actionType("category_dropdown")
                .categoryId(newsCategoryId)
                .isEnabled(true)
                .ordinal(5)
                .createdAt(now)
                .updatedAt(now)
                .build()
        );

        for (HomepageCard card : cards) {
            homepageCardRepository.saveEntity(server, card);
        }
    }

}
