package gg.modl.backend.support;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;

public final class TestDatabase {

    private final MongoClient client;
    private final MongoDatabase serverDb;
    private static volatile TestDatabase instance;

    private TestDatabase(String uri, String domain) {
        this.client = MongoClients.create(uri);
        // Extract subdomain from full domain (e.g. "byteful.modl.gg" -> "byteful")
        String subdomain = domain.contains(".") ? domain.substring(0, domain.indexOf('.')) : domain;
        this.serverDb = client.getDatabase("server_" + subdomain);
    }

    public static TestDatabase getInstance() {
        if (instance == null) {
            synchronized (TestDatabase.class) {
                if (instance == null) {
                    String uri = StagingCredentials.mongoUri();
                    String domain = StagingCredentials.serverDomain();
                    instance = new TestDatabase(uri, domain);
                }
            }
        }
        return instance;
    }

    public static boolean isAvailable() {
        String uri = StagingCredentials.mongoUri();
        return uri != null && !uri.isBlank();
    }

    // ── Collection accessors ──

    public Document findPunishmentInPlayer(String playerUuid, String punishmentId) {
        Document player = findPlayerByUuid(playerUuid);
        if (player == null) {
            return null;
        }
        List<Document> punishments = player.getList("punishments", Document.class);
        if (punishments == null) {
            return null;
        }
        for (Document p : punishments) {
            String id = null;
            Object idObj = p.get("_id");
            if (idObj instanceof ObjectId oid) {
                id = oid.toHexString();
            } else if (idObj instanceof String s) {
                id = s;
            }
            // Also check the "id" field
            if (punishmentId.equals(id) || punishmentId.equals(p.getString("id"))) {
                return p;
            }
        }
        return null;
    }

    public Document findPlayerByUuid(String uuid) {
        return players().find(eq("minecraftUuid", uuid)).first();
    }

    public MongoCollection<Document> players() {return serverDb.getCollection("players");}

    public Document findTicketById(String ticketId) {
        // Try string _id first, then ObjectId
        Document doc = tickets().find(eq("_id", ticketId)).first();
        if (doc == null) {
            try {
                doc = tickets().find(eq("_id", new ObjectId(ticketId))).first();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return doc;
    }

    public MongoCollection<Document> tickets() {return serverDb.getCollection("tickets");}

    public Document findStaffByUsername(String username) {
        return staff().find(eq("username", username)).first();
    }

    public MongoCollection<Document> staff() {return serverDb.getCollection("staffs");}

    public Document findRoleById(String roleId) {
        Document doc = roles().find(eq("_id", roleId)).first();
        if (doc == null) {
            try {
                doc = roles().find(eq("_id", new ObjectId(roleId))).first();
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (doc == null) {
            doc = roles().find(eq("id", roleId)).first();
        }
        return doc;
    }

    // ── Query helpers ──

    public MongoCollection<Document> roles() {return serverDb.getCollection("staffroles");}

    public Document findSettingsByType(String type) {
        return settings().find(eq("type", type)).first();
    }

    public MongoCollection<Document> settings() {return serverDb.getCollection("settings");}

    public Document findHomepageCardById(String cardId) {
        Document doc = homepageCards().find(eq("_id", cardId)).first();
        if (doc == null) {
            try {
                doc = homepageCards().find(eq("_id", new ObjectId(cardId))).first();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return doc;
    }

    public MongoCollection<Document> homepageCards() {return serverDb.getCollection("homepagecards");}

    public Document findKbCategoryById(String categoryId) {
        Document doc = kbCategories().find(eq("_id", categoryId)).first();
        if (doc == null) {
            try {
                doc = kbCategories().find(eq("_id", new ObjectId(categoryId))).first();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return doc;
    }

    public MongoCollection<Document> kbCategories() {return serverDb.getCollection("knowledgebasecategories");}

    public Document findKbArticleById(String articleId) {
        Document doc = kbArticles().find(eq("_id", articleId)).first();
        if (doc == null) {
            try {
                doc = kbArticles().find(eq("_id", new ObjectId(articleId))).first();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return doc;
    }

    public MongoCollection<Document> kbArticles() {return serverDb.getCollection("knowledgebasearticles");}

    public void close() {
        client.close();
        synchronized (TestDatabase.class) {
            instance = null;
        }
    }
}
