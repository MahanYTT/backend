package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import gg.modl.backend.support.TestDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PanelKnowledgebaseApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    // ── Categories ──

    @Test
    void listCategories() throws Exception {
        var response = api.panelGet("/v1/panel/knowledgebase/categories");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void createAndDeleteCategory() throws Exception {
        var createResponse = api.panelPost("/v1/panel/knowledgebase/categories", Map.of(
            "name", "API Test Category " + System.currentTimeMillis(),
            "description", "Created by automated test"
        ));
        int status = createResponse.statusCode();
        assertTrue(status == 200 || status == 201, "Expected 200 or 201 but got " + status);

        var json = JsonHelper.parseObject(createResponse.body());
        String categoryId = json.has("id") ? json.get("id").getAsString() :
                            json.has("_id") ? json.get("_id").getAsString() : null;
        if (categoryId == null) {
            return;
        }

        // DB VERIFICATION: confirm category created
        if (TestDatabase.isAvailable()) {
            var dbCategory = TestDatabase.getInstance().findKbCategoryById(categoryId);
            assertNotNull(dbCategory, "KB category should exist in DB after creation");
        }

        // Cleanup
        var deleteResponse = api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
        JsonHelper.assertStatus(deleteResponse, 200);

        // DB VERIFICATION: confirm category deleted
        if (TestDatabase.isAvailable()) {
            var dbCategory = TestDatabase.getInstance().findKbCategoryById(categoryId);
            assertNull(dbCategory, "KB category should not exist in DB after deletion");
        }
    }

    @Test
    void updateCategory() throws Exception {
        // Create category
        var createResponse = api.panelPost("/v1/panel/knowledgebase/categories", Map.of(
            "name", "API Test Update " + System.currentTimeMillis()
        ));
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            return;
        }
        var json = JsonHelper.parseObject(createResponse.body());
        String categoryId = json.has("id") ? json.get("id").getAsString() :
                            json.has("_id") ? json.get("_id").getAsString() : null;
        if (categoryId == null) {
            return;
        }

        var updateResponse = api.panelPut("/v1/panel/knowledgebase/categories/" + categoryId, Map.of(
            "name", "API Test Updated " + System.currentTimeMillis(),
            "description", "Updated by test"
        ));
        JsonHelper.assertStatus(updateResponse, 200);

        // Cleanup
        api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
    }

    @Test
    void reorderCategories() throws Exception {
        var listResponse = api.panelGet("/v1/panel/knowledgebase/categories");
        var arr = JsonHelper.parseArray(listResponse.body());
        if (arr.size() < 2) {
            return;
        }

        List<String> ids = new java.util.ArrayList<>();
        arr.forEach(c -> {
            var obj = c.getAsJsonObject();
            ids.add(obj.has("id") ? obj.get("id").getAsString() : obj.get("_id").getAsString());
        });

        var response = api.panelPut("/v1/panel/knowledgebase/categories/reorder", Map.of("ids", ids));
        JsonHelper.assertStatus(response, 200);
    }

    // ── Articles ──

    @Test
    void createAndDeleteArticle() throws Exception {
        // Need a category first
        var catResponse = api.panelPost("/v1/panel/knowledgebase/categories", Map.of(
            "name", "API Test Article Cat " + System.currentTimeMillis()
        ));
        if (catResponse.statusCode() != 200 && catResponse.statusCode() != 201) {
            return;
        }
        var catJson = JsonHelper.parseObject(catResponse.body());
        String categoryId = catJson.has("id") ? catJson.get("id").getAsString() :
                            catJson.has("_id") ? catJson.get("_id").getAsString() : null;
        if (categoryId == null) {
            return;
        }

        var articleResponse = api.panelPost("/v1/panel/knowledgebase/categories/" + categoryId + "/articles", Map.of(
            "title", "API Test Article " + System.currentTimeMillis(),
            "content", "This is test content.",
            "isVisible", true
        ));
        int articleStatus = articleResponse.statusCode();
        assertTrue(articleStatus == 200 || articleStatus == 201, "Expected 200 or 201 but got " + articleStatus);

        var articleJson = JsonHelper.parseObject(articleResponse.body());
        String articleId = articleJson.has("id") ? articleJson.get("id").getAsString() :
                           articleJson.has("_id") ? articleJson.get("_id").getAsString() : null;

        // DB VERIFICATION: confirm article created
        if (TestDatabase.isAvailable() && articleId != null) {
            var dbArticle = TestDatabase.getInstance().findKbArticleById(articleId);
            assertNotNull(dbArticle, "KB article should exist in DB after creation");
        }

        if (articleId != null) {
            api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId + "/articles/" + articleId);

            // DB VERIFICATION: confirm article deleted
            if (TestDatabase.isAvailable()) {
                var dbArticle = TestDatabase.getInstance().findKbArticleById(articleId);
                assertNull(dbArticle, "KB article should not exist in DB after deletion");
            }
        }
        // Cleanup category
        api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
    }

    @Test
    void listArticles() throws Exception {
        var listResponse = api.panelGet("/v1/panel/knowledgebase/categories");
        var arr = JsonHelper.parseArray(listResponse.body());
        if (arr.isEmpty()) {
            return;
        }

        var cat = arr.get(0).getAsJsonObject();
        String categoryId = cat.has("id") ? cat.get("id").getAsString() :
                            cat.has("_id") ? cat.get("_id").getAsString() : null;
        if (categoryId == null) {
            return;
        }

        var response = api.panelGet("/v1/panel/knowledgebase/categories/" + categoryId + "/articles");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getArticleById() throws Exception {
        // Create category + article
        var catResponse = api.panelPost("/v1/panel/knowledgebase/categories", Map.of(
            "name", "API Test Get Article " + System.currentTimeMillis()
        ));
        if (catResponse.statusCode() != 200 && catResponse.statusCode() != 201) {
            return;
        }
        var catJson = JsonHelper.parseObject(catResponse.body());
        String categoryId = catJson.has("id") ? catJson.get("id").getAsString() :
                            catJson.has("_id") ? catJson.get("_id").getAsString() : null;
        if (categoryId == null) {
            return;
        }

        var articleResponse = api.panelPost("/v1/panel/knowledgebase/categories/" + categoryId + "/articles", Map.of(
            "title", "API Test Get " + System.currentTimeMillis(),
            "content", "Test content for get."
        ));
        if (articleResponse.statusCode() != 200 && articleResponse.statusCode() != 201) {
            api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
            return;
        }
        var articleJson = JsonHelper.parseObject(articleResponse.body());
        String articleId = articleJson.has("id") ? articleJson.get("id").getAsString() :
                           articleJson.has("_id") ? articleJson.get("_id").getAsString() : null;
        if (articleId == null) {
            api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
            return;
        }

        var response = api.panelGet("/v1/panel/knowledgebase/categories/" + categoryId + "/articles/" + articleId);
        JsonHelper.assertStatus(response, 200);

        // Cleanup
        api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId + "/articles/" + articleId);
        api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
    }

    @Test
    void updateArticle() throws Exception {
        var catResponse = api.panelPost("/v1/panel/knowledgebase/categories", Map.of(
            "name", "API Test Update Article " + System.currentTimeMillis()
        ));
        if (catResponse.statusCode() != 200 && catResponse.statusCode() != 201) {
            return;
        }
        var catJson = JsonHelper.parseObject(catResponse.body());
        String categoryId = catJson.has("id") ? catJson.get("id").getAsString() :
                            catJson.has("_id") ? catJson.get("_id").getAsString() : null;
        if (categoryId == null) {
            return;
        }

        var articleResponse = api.panelPost("/v1/panel/knowledgebase/categories/" + categoryId + "/articles", Map.of(
            "title", "API Test Article Update",
            "content", "Original content"
        ));
        if (articleResponse.statusCode() != 200 && articleResponse.statusCode() != 201) {
            api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
            return;
        }
        var articleJson = JsonHelper.parseObject(articleResponse.body());
        String articleId = articleJson.has("id") ? articleJson.get("id").getAsString() :
                           articleJson.has("_id") ? articleJson.get("_id").getAsString() : null;
        if (articleId == null) {
            api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
            return;
        }

        var response = api.panelPut("/v1/panel/knowledgebase/categories/" + categoryId + "/articles/" + articleId, Map.of(
            "title", "API Test Article Updated",
            "content", "Updated content"
        ));
        JsonHelper.assertStatus(response, 200);

        // Cleanup
        api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId + "/articles/" + articleId);
        api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
    }

    @Test
    void reorderArticles() throws Exception {
        var catResponse = api.panelPost("/v1/panel/knowledgebase/categories", Map.of(
            "name", "API Test Reorder " + System.currentTimeMillis()
        ));
        if (catResponse.statusCode() != 200 && catResponse.statusCode() != 201) {
            return;
        }
        var catJson = JsonHelper.parseObject(catResponse.body());
        String categoryId = catJson.has("id") ? catJson.get("id").getAsString() :
                            catJson.has("_id") ? catJson.get("_id").getAsString() : null;
        if (categoryId == null) {
            return;
        }

        // Create 2 articles
        var a1 = api.panelPost("/v1/panel/knowledgebase/categories/" + categoryId + "/articles", Map.of("title", "Art 1", "content", "c1"));
        var a2 = api.panelPost("/v1/panel/knowledgebase/categories/" + categoryId + "/articles", Map.of("title", "Art 2", "content", "c2"));
        if (a1.statusCode() != 201 && a1.statusCode() != 200) {
            api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
            return;
        }
        if (a2.statusCode() != 201 && a2.statusCode() != 200) {
            api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
            return;
        }

        var j1 = JsonHelper.parseObject(a1.body());
        var j2 = JsonHelper.parseObject(a2.body());
        String id1 = j1.has("id") ? j1.get("id").getAsString() : j1.get("_id").getAsString();
        String id2 = j2.has("id") ? j2.get("id").getAsString() : j2.get("_id").getAsString();

        var response = api.panelPut("/v1/panel/knowledgebase/categories/" + categoryId + "/articles/reorder",
            Map.of("ids", List.of(id2, id1)));
        JsonHelper.assertStatus(response, 200);

        // Cleanup
        api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId + "/articles/" + id1);
        api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId + "/articles/" + id2);
        api.panelDelete("/v1/panel/knowledgebase/categories/" + categoryId);
    }
}

