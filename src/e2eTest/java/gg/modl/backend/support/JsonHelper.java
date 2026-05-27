package gg.modl.backend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.http.HttpResponse;

public final class JsonHelper {

    private static final Gson GSON = new GsonBuilder().create();

    private JsonHelper() {}

    public static JsonArray parseArray(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static void assertStatus(HttpResponse<String> response, int expectedCode) {
        assertEquals(expectedCode, response.statusCode(),
            () -> "Expected status " + expectedCode + " but got " + response.statusCode()
                  + " | body: " + truncate(response.body(), 500));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static void assertJsonHas(HttpResponse<String> response, String field) {
        JsonObject json = parseObject(response.body());
        assertTrue(json.has(field),
            () -> "Expected JSON to have field '" + field + "' but got: " + truncate(response.body(), 500));
    }

    public static JsonObject parseObject(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
