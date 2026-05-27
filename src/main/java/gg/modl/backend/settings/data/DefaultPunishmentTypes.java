package gg.modl.backend.settings.data;

import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

@UtilityClass
public class DefaultPunishmentTypes {

    public static List<PunishmentType> getAll() {
        List<PunishmentType> types = new ArrayList<>();
        types.addAll(getAdministrativeTypes());
        types.addAll(getSocialTypes());
        types.addAll(getGameplayTypes());
        return types;
    }

    public static List<PunishmentType> getAdministrativeTypes() {
        return List.of(
            createKick(),
            createManualMute(),
            createManualBan(),
            createSecurityBan(),
            createLinkedBan(),
            createBlacklist()
        );
    }

    private static PunishmentType createKick() {
        return PunishmentType.builder()
            .id(0)
            .name("Kick")
            .category("Administrative")
            .customizable(false)
            .ordinal(0)
            .staffDescription("Kick a player.")
            .playerDescription("BOOT!")
            .canBeAltBlocking(false)
            .canBeStatWiping(false)
            .appealable(false)
            .build();
    }

    private static PunishmentType createManualMute() {
        return PunishmentType.builder()
            .id(1)
            .name("Manual Mute")
            .category("Administrative")
            .customizable(false)
            .ordinal(1)
            .staffDescription("Manually mute a player.")
            .playerDescription("You have been silenced.")
            .canBeAltBlocking(false)
            .canBeStatWiping(false)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static AppealForm createDefaultAppealForm() {
        return AppealForm.builder()
            .sections(List.of(
                AppealFormSection.builder()
                    .id("appeal_reason_section")
                    .title("Appeal Information")
                    .description("Explain why you believe this punishment should be amended")
                    .order(0)
                    .build()
            ))
            .fields(List.of(
                AppealFormField.builder()
                    .id("why")
                    .type("textarea")
                    .label("Why should this punishment be amended?")
                    .description("Please provide context and any relevant information to support your appeal")
                    .required(true)
                    .order(1)
                    .sectionId("appeal_reason_section")
                    .build()
            ))
            .build();
    }

    private static PunishmentType createManualBan() {
        return PunishmentType.builder()
            .id(2)
            .name("Manual Ban")
            .category("Administrative")
            .customizable(false)
            .ordinal(2)
            .staffDescription("Manually ban a player.")
            .playerDescription("The ban hammer has spoken.")
            .canBeAltBlocking(true)
            .canBeStatWiping(true)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createSecurityBan() {
        return PunishmentType.builder()
            .id(3)
            .name("Security Ban")
            .category("Administrative")
            .customizable(false)
            .ordinal(3)
            .staffDescription("Compromised or potentially compromised account.")
            .playerDescription("Suspicious activity has been detected on your account. Please secure your account and appeal this ban.")
            .canBeAltBlocking(false)
            .canBeStatWiping(false)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createLinkedBan() {
        return PunishmentType.builder()
            .id(4)
            .name("Linked Ban")
            .category("Administrative")
            .customizable(false)
            .ordinal(4)
            .staffDescription("Usually automatically applied due to ban evasion.")
            .playerDescription("Evading bans through the use of alternate accounts or sharing your account is strictly prohibited.")
            .canBeAltBlocking(false)
            .canBeStatWiping(false)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createBlacklist() {
        return PunishmentType.builder()
            .id(5)
            .name("Blacklist")
            .category("Administrative")
            .customizable(false)
            .ordinal(5)
            .staffDescription("Remove a player (unappealable).")
            .playerDescription("You are blacklisted from the server.")
            .canBeAltBlocking(true)
            .canBeStatWiping(true)
            .appealable(false)
            .build();
    }

    public static List<PunishmentType> getSocialTypes() {
        return List.of(
            createChatAbuse(),
            createAntiSocial(),
            createTargeting(),
            createBadContent(),
            createBadUsername(),
            createBadSkin()
        );
    }

    private static PunishmentType createChatAbuse() {
        return PunishmentType.builder()
            .id(8)
            .name("Chat Abuse")
            .category("Social")
            .customizable(true)
            .ordinal(6)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(6, "hours", "mute"),
                    new DurationDetail(1, "days", "mute"),
                    new DurationDetail(3, "days", "mute")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(1, "days", "mute"),
                    new DurationDetail(3, "days", "mute"),
                    new DurationDetail(7, "days", "mute")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(3, "days", "mute"),
                    new DurationDetail(7, "days", "mute"),
                    new DurationDetail(14, "days", "mute")
                )
            ))
            .points(new PunishmentPoints(1, 1, 2))
            .staffDescription("Inappropriate language, excessive caps, or disruptive chat behavior.")
            .playerDescription("Public chat channels are reserved for decent messages.")
            .canBeAltBlocking(false)
            .canBeStatWiping(false)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createAntiSocial() {
        return PunishmentType.builder()
            .id(9)
            .name("Anti Social")
            .category("Social")
            .customizable(true)
            .ordinal(7)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(3, "days", "mute"),
                    new DurationDetail(7, "days", "mute"),
                    new DurationDetail(14, "days", "mute")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(7, "days", "mute"),
                    new DurationDetail(30, "days", "mute"),
                    new DurationDetail(90, "days", "mute")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(30, "days", "mute"),
                    new DurationDetail(90, "days", "mute"),
                    new DurationDetail(180, "days", "mute")
                )
            ))
            .points(new PunishmentPoints(2, 3, 4))
            .staffDescription("Hostile, toxic, or antisocial behavior that creates a negative environment.")
            .playerDescription("Anti-social and disruptive behavior is strictly prohibited from public channels.")
            .canBeAltBlocking(false)
            .canBeStatWiping(false)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createTargeting() {
        return PunishmentType.builder()
            .id(10)
            .name("Targeting")
            .category("Social")
            .customizable(true)
            .ordinal(8)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(7, "days", "ban"),
                    new DurationDetail(14, "days", "ban"),
                    new DurationDetail(30, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(90, "days", "ban"),
                    new DurationDetail(180, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(90, "days", "ban"),
                    new DurationDetail(180, "days", "ban"),
                    new DurationDetail(365, "days", "ban")
                )
            ))
            .points(new PunishmentPoints(4, 6, 10))
            .staffDescription("Persistent harassment, bullying, or targeting of specific players with malicious intent.")
            .playerDescription("This server has a zero tolerance policy on targeting individuals.")
            .canBeAltBlocking(true)
            .canBeStatWiping(false)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createBadContent() {
        return PunishmentType.builder()
            .id(11)
            .name("Bad Content")
            .category("Social")
            .customizable(true)
            .ordinal(9)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(1, "days", "ban"),
                    new DurationDetail(7, "days", "ban"),
                    new DurationDetail(14, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(7, "days", "ban"),
                    new DurationDetail(14, "days", "ban"),
                    new DurationDetail(30, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(60, "days", "ban"),
                    new DurationDetail(90, "days", "ban")
                )
            ))
            .points(new PunishmentPoints(3, 4, 5))
            .staffDescription("Inappropriate content including sexual references, doxxing, links to harmful sites.")
            .playerDescription("Sharing inappropriate content of any kind is strictly prohibited.")
            .canBeAltBlocking(true)
            .canBeStatWiping(false)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createBadUsername() {
        return PunishmentType.builder()
            .id(18)
            .name("Bad Username")
            .category("Social")
            .customizable(true)
            .ordinal(10)
            .permanentUntilUsernameChange(true)
            .staffDescription("Username violates server guidelines (inappropriate, offensive, or misleading).")
            .playerDescription("Your username violates our community guidelines. Please change your username to something appropriate to continue playing.")
            .canBeAltBlocking(false)
            .canBeStatWiping(false)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createBadSkin() {
        return PunishmentType.builder()
            .id(19)
            .name("Bad Skin")
            .category("Social")
            .customizable(true)
            .ordinal(11)
            .permanentUntilSkinChange(true)
            .staffDescription("Player skin violates server guidelines (inappropriate, offensive, or misleading).")
            .playerDescription("Your Minecraft skin violates our community guidelines. Please change your skin to something appropriate to continue playing.")
            .canBeAltBlocking(false)
            .canBeStatWiping(false)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    public static List<PunishmentType> getGameplayTypes() {
        return List.of(
            createTeamAbuse(),
            createGameAbuse(),
            createCheating(),
            createGameTrading(),
            createAccountAbuse(),
            createSystemsAbuse()
        );
    }

    private static PunishmentType createTeamAbuse() {
        return PunishmentType.builder()
            .id(12)
            .name("Team Abuse")
            .category("Gameplay")
            .customizable(true)
            .ordinal(12)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(6, "hours", "ban"),
                    new DurationDetail(12, "hours", "ban"),
                    new DurationDetail(3, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(12, "hours", "ban"),
                    new DurationDetail(3, "days", "ban"),
                    new DurationDetail(7, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(3, "days", "ban"),
                    new DurationDetail(7, "days", "ban"),
                    new DurationDetail(14, "days", "ban")
                )
            ))
            .points(new PunishmentPoints(2, 2, 3))
            .staffDescription("Intentionally harming teammates, cross-teaming, or aiding cheaters.")
            .playerDescription("Please be considerate to fellow players by not team-griefing, aiding cheaters, or cross-teaming.")
            .canBeAltBlocking(true)
            .canBeStatWiping(true)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createGameAbuse() {
        return PunishmentType.builder()
            .id(13)
            .name("Game Abuse")
            .category("Gameplay")
            .customizable(true)
            .ordinal(13)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(1, "days", "ban"),
                    new DurationDetail(3, "days", "ban"),
                    new DurationDetail(7, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(7, "days", "ban"),
                    new DurationDetail(14, "days", "ban"),
                    new DurationDetail(14, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(90, "days", "ban")
                )
            ))
            .points(new PunishmentPoints(2, 3, 5))
            .staffDescription("Violating game specific rules for fair play.")
            .playerDescription("Violating game specific rules for competitive fair-play.")
            .canBeAltBlocking(true)
            .canBeStatWiping(true)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createCheating() {
        return PunishmentType.builder()
            .id(14)
            .name("Cheating")
            .category("Gameplay")
            .customizable(true)
            .ordinal(14)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(3, "days", "ban"),
                    new DurationDetail(14, "days", "ban"),
                    new DurationDetail(30, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(14, "days", "ban"),
                    new DurationDetail(60, "days", "ban"),
                    new DurationDetail(180, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(90, "days", "ban"),
                    new DurationDetail(0, "days", "permanent ban")
                )
            ))
            .points(new PunishmentPoints(5, 7, 9))
            .staffDescription("Using hacks, mods, exploits, or other software to gain an unfair advantage.")
            .playerDescription("Cheating through the use of client-side modifications or game exploits is strictly prohibited.")
            .canBeAltBlocking(true)
            .canBeStatWiping(true)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createGameTrading() {
        return PunishmentType.builder()
            .id(15)
            .name("Game Trading")
            .category("Gameplay")
            .customizable(true)
            .ordinal(15)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(14, "days", "ban"),
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(60, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(90, "days", "ban"),
                    new DurationDetail(180, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(0, "days", "permanent ban"),
                    new DurationDetail(0, "days", "permanent ban"),
                    new DurationDetail(0, "days", "permanent ban")
                )
            ))
            .points(new PunishmentPoints(4, 6, 10))
            .staffDescription("Trading or selling in-game items, content, or services on unauthorized third-party platforms.")
            .playerDescription("Trading or selling in-game items on unauthorized third-party platforms is strictly prohibited.")
            .canBeAltBlocking(true)
            .canBeStatWiping(true)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createAccountAbuse() {
        return PunishmentType.builder()
            .id(16)
            .name("Account Abuse")
            .category("Gameplay")
            .customizable(true)
            .ordinal(16)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(14, "days", "ban"),
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(60, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(90, "days", "ban"),
                    new DurationDetail(180, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(0, "days", "permanent ban"),
                    new DurationDetail(0, "days", "permanent ban"),
                    new DurationDetail(0, "days", "permanent ban")
                )
            ))
            .points(new PunishmentPoints(4, 6, 10))
            .staffDescription("Account sharing, alt-account boosting, selling/trading accounts.")
            .playerDescription("Misuse of accounts for financial or levelling gain is prohibited.")
            .canBeAltBlocking(true)
            .canBeStatWiping(true)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }

    private static PunishmentType createSystemsAbuse() {
        return PunishmentType.builder()
            .id(17)
            .name("Systems Abuse")
            .category("Gameplay")
            .customizable(true)
            .ordinal(17)
            .durations(new PunishmentDurations(
                new OffenseLevelDurations(
                    new DurationDetail(3, "days", "ban"),
                    new DurationDetail(7, "days", "ban"),
                    new DurationDetail(14, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(14, "days", "ban"),
                    new DurationDetail(30, "days", "ban"),
                    new DurationDetail(90, "days", "ban")
                ),
                new OffenseLevelDurations(
                    new DurationDetail(90, "days", "ban"),
                    new DurationDetail(180, "days", "ban"),
                    new DurationDetail(365, "days", "ban")
                )
            ))
            .points(new PunishmentPoints(2, 3, 5))
            .staffDescription("Abusing server functions by opening redundant tickets, creating lag machines, etc.")
            .playerDescription("Using server systems in an unintended and harmful way is strictly prohibited.")
            .canBeAltBlocking(true)
            .canBeStatWiping(true)
            .appealable(true)
            .appealForm(createDefaultAppealForm())
            .build();
    }
}
