package gg.modl.backend.support;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Updates;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bson.Document;

public final class TestDataProvider {

    // Defaults (used when MongoDB is not available)
    private static final String DEFAULT_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String DEFAULT_USERNAME = "Notch";
    private static final int DEFAULT_TYPE_ORDINAL = 14;

    private static volatile boolean initialized = false;
    private static List<PlayerInfo> players;
    private static PlayerInfo playerWithPunishments;
    private static PlayerInfo playerWithoutPunishments;
    private static List<PunishmentTypeInfo> punishmentTypes;
    private static List<StaffInfo> staffMembers;
    private static List<RoleInfo> rolesList;

    private TestDataProvider() {}

    public static List<PlayerInfo> getPlayers() {
        initialize();
        return Collections.unmodifiableList(players);
    }

    private static synchronized void initialize() {
        if (initialized) {
            return;
        }

        if (!TestDatabase.isAvailable()) {
            loadDefaults();
            initialized = true;
            return;
        }

        try {
            loadFromDatabase();
        } catch (Throwable e) {
            System.err.println("[TestDataProvider] Failed to load from DB, using defaults: " + e.getMessage());
            loadDefaults();
        }
        initialized = true;
    }

    private static void loadDefaults() {
        players = List.of(new PlayerInfo(DEFAULT_UUID, DEFAULT_USERNAME));
        playerWithPunishments = new PlayerInfo(DEFAULT_UUID, DEFAULT_USERNAME);
        playerWithoutPunishments = new PlayerInfo(DEFAULT_UUID, DEFAULT_USERNAME);
        punishmentTypes = List.of(new PunishmentTypeInfo(DEFAULT_TYPE_ORDINAL, "Default", "BAN"));
        staffMembers = List.of();
        rolesList = List.of();
    }

    private static void loadFromDatabase() {
        TestDatabase db = TestDatabase.getInstance();

        // Fix any corrupted usernames from previous test runs before loading data
        cleanupCorruptedUsernames(db);

        // Load players
        players = new ArrayList<>();
        playerWithPunishments = null;
        playerWithoutPunishments = null;

        try (MongoCursor<Document> cursor = db.players().find().limit(20).iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String uuid = doc.getString("minecraftUuid");
                // Player model uses "usernames" array, not "username" field
                String username = null;
                Object usernamesObj = doc.get("usernames");
                if (usernamesObj instanceof List<?> usernamesList && !usernamesList.isEmpty()) {
                    // Iterate from end to find the last valid Document entry
                    for (int i = usernamesList.size() - 1; i >= 0; i--) {
                        Object entry = usernamesList.get(i);
                        if (entry instanceof Document d) {
                            username = d.getString("username");
                            if (username != null) {
                                break;
                            }
                        }
                    }
                }
                if (uuid == null || username == null) {
                    continue;
                }

                PlayerInfo info = new PlayerInfo(uuid, username);
                players.add(info);

                List<Document> punishments = doc.getList("punishments", Document.class);
                if (punishments != null && !punishments.isEmpty()) {
                    if (playerWithPunishments == null) {
                        playerWithPunishments = info;
                    }
                } else {
                    if (playerWithoutPunishments == null) {
                        playerWithoutPunishments = info;
                    }
                }

                if (players.size() >= 5 && playerWithPunishments != null && playerWithoutPunishments != null) {
                    break;
                }
            }
        }

        if (players.isEmpty()) {
            players = List.of(new PlayerInfo(DEFAULT_UUID, DEFAULT_USERNAME));
        }
        if (playerWithPunishments == null) {
            playerWithPunishments = players.get(0);
        }
        if (playerWithoutPunishments == null) {
            playerWithoutPunishments = players.get(0);
        }

        // Load punishment types from settings
        punishmentTypes = new ArrayList<>();
        Document punishmentSettings = db.findSettingsByType("punishment_types");
        if (punishmentSettings != null) {
            Object dataObj = punishmentSettings.get("data");
            if (dataObj instanceof Document data) {
                List<Document> types = data.getList("types", Document.class);
                if (types != null) {
                    for (Document type : types) {
                        Integer ordinal = type.getInteger("ordinal");
                        String name = type.getString("name");
                        String category = type.getString("category");
                        if (ordinal != null && name != null) {
                            punishmentTypes.add(new PunishmentTypeInfo(ordinal, name, category != null ? category : "BAN"));
                        }
                    }
                }
            }
        }
        if (punishmentTypes.isEmpty()) {
            punishmentTypes = List.of(new PunishmentTypeInfo(DEFAULT_TYPE_ORDINAL, "Default", "BAN"));
        }

        // Load staff members
        staffMembers = new ArrayList<>();
        try (MongoCursor<Document> cursor = db.staff().find().limit(10).iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                Object idObj = doc.get("_id");
                String id = idObj != null ? idObj.toString() : null;
                String username = doc.getString("username");
                String role = doc.getString("role");
                if (id != null && username != null) {
                    staffMembers.add(new StaffInfo(id, username, role != null ? role : ""));
                }
            }
        }

        // Load roles
        rolesList = new ArrayList<>();
        try (MongoCursor<Document> cursor = db.roles().find().limit(10).iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                Object idObj = doc.get("_id");
                String id = idObj != null ? idObj.toString() : null;
                String name = doc.getString("name");
                if (id != null && name != null) {
                    rolesList.add(new RoleInfo(id, name));
                }
            }
        }
    }

    // ── Public accessors ──

    private static void cleanupCorruptedUsernames(TestDatabase db) {
        try (MongoCursor<Document> cursor = db.players().find().iterator()) {
            while (cursor.hasNext()) {
                Document player = cursor.next();
                Object usernamesObj = player.get("usernames");
                if (!(usernamesObj instanceof List<?> usernamesList)) {
                    continue;
                }

                boolean corrupted = false;
                List<Document> clean = new ArrayList<>();
                for (Object entry : usernamesList) {
                    if (entry instanceof Document doc) {
                        clean.add(doc);
                    } else if (entry instanceof List<?> nested) {
                        corrupted = true;
                        for (Object n : nested) {
                            if (n instanceof Document doc) {
                                clean.add(doc);
                            }
                        }
                    }
                }
                if (corrupted) {
                    db.players().updateOne(eq("_id", player.get("_id")), Updates.set("usernames", clean));
                }
            }
        }
    }

    public static PlayerInfo getPlayerWithPunishments() {
        initialize();
        return playerWithPunishments;
    }

    public static PlayerInfo getPlayerWithoutPunishments() {
        initialize();
        return playerWithoutPunishments;
    }

    public static List<PunishmentTypeInfo> getPunishmentTypes() {
        initialize();
        return Collections.unmodifiableList(punishmentTypes);
    }

    public static List<StaffInfo> getStaffMembers() {
        initialize();
        return Collections.unmodifiableList(staffMembers);
    }

    public static List<RoleInfo> getRoles() {
        initialize();
        return Collections.unmodifiableList(rolesList);
    }

    // ── Record types ──

    public record PlayerInfo(String uuid, String username) {}

    public record PunishmentTypeInfo(int ordinal, String name, String category) {}

    public record StaffInfo(String id, String username, String role) {}

    public record RoleInfo(String id, String name) {}
}
