package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PublicKnowledgebaseApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPublicApiAvailable(), StagingCredentials.publicApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void getCategories() throws Exception {
        var response = api.publicGet("/v1/public/knowledgebase/categories");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getArticlesByCategory() throws Exception {
        // Get a category ID first
        var catResponse = api.publicGet("/v1/public/knowledgebase/categories");
        var arr = JsonHelper.parseArray(catResponse.body());
        if (arr.isEmpty()) {
            return;
        }

        var cat = arr.get(0).getAsJsonObject();
        String categoryId = cat.has("id") ? cat.get("id").getAsString() :
                            cat.has("_id") ? cat.get("_id").getAsString() : null;
        if (categoryId == null) {
            return;
        }

        var response = api.publicGet("/v1/public/knowledgebase/categories/" + categoryId + "/articles");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getArticleByIdOrSlug() throws Exception {
        // Try with a nonexistent slug
        var response = api.publicGet("/v1/public/knowledgebase/articles/nonexistent-slug");
        assertEquals(404, response.statusCode());
    }

    @Test
    void searchArticles() throws Exception {
        var response = api.publicGet("/v1/public/knowledgebase/search?q=test");
        JsonHelper.assertStatus(response, 200);
    }
}

