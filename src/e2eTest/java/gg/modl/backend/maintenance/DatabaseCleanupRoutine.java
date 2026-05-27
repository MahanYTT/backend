package gg.modl.backend.maintenance;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Updates;
import gg.modl.backend.support.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * One-shot maintenance routine that repairs corrupted {@code usernames} arrays on Player
 * documents. Earlier test runs occasionally inserted nested {@code List<Document>} values where
 * a flat {@code List<Document>} was expected; this script unwraps them.
 *
 * <p>Lives in the e2eTest source set because it operates against the live staging Mongo via
 * {@link TestDatabase}. {@code @Disabled} so it never runs as part of the normal e2eTest
 * task — invoke explicitly with
 * {@code ./gradlew e2eTest --tests DatabaseCleanupRoutine -PrunDisabled} or remove the
 * annotation locally before running.
 */
@Disabled("Manual maintenance routine — enable explicitly to run against staging Mongo.")
class DatabaseCleanupRoutine {

    @Test
    void fixCorruptedUsernames() {
        if (!TestDatabase.isAvailable()) {
            throw new IllegalStateException(
                "DatabaseCleanupRoutine requires MODL_MONGO_URI; configure staging credentials before running.");
        }
        var db = TestDatabase.getInstance();

        int fixed = 0;
        try (MongoCursor<Document> cursor = db.players().find().iterator()) {
            while (cursor.hasNext()) {
                Document player = cursor.next();
                Object usernamesObj = player.get("usernames");
                if (!(usernamesObj instanceof List<?> usernamesList)) {
                    continue;
                }

                boolean corrupted = false;
                List<Document> cleanUsernames = new ArrayList<>();

                for (Object entry : usernamesList) {
                    if (entry instanceof Document doc) {
                        cleanUsernames.add(doc);
                    } else if (entry instanceof List<?> nestedList) {
                        corrupted = true;
                        for (Object nested : nestedList) {
                            if (nested instanceof Document doc) {
                                cleanUsernames.add(doc);
                            }
                        }
                    }
                }

                if (corrupted) {
                    String uuid = player.getString("minecraftUuid");
                    System.out.println("Fixing corrupted usernames for player: " + uuid);
                    db.players().updateOne(
                        eq("_id", player.get("_id")),
                        Updates.set("usernames", cleanUsernames)
                    );
                    fixed++;
                }
            }
        }

        System.out.println("Fixed " + fixed + " player(s) with corrupted usernames");
    }
}
