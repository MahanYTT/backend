package gg.modl.backend.support;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ApiClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String sessionToken;
    private final String serverDomain;
    private final String panelOrigin;
    private final Gson gson = new Gson();

    public ApiClient() {
        this.baseUrl = StagingCredentials.baseUrl();
        this.apiKey = StagingCredentials.apiKey();
        this.sessionToken = StagingCredentials.sessionToken();
        this.serverDomain = StagingCredentials.serverDomain();
        this.panelOrigin = resolveOrigin(
            StagingCredentials.panelOrigin() != null
            ? StagingCredentials.panelOrigin()
            : "https://admin.modl.gg"
        );
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    // ── Minecraft auth: X-API-Key + X-Server-Domain ──

    private static String resolveOrigin(String rawBaseUrl) {
        URI uri = URI.create(rawBaseUrl);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid base URL for origin derivation: " + rawBaseUrl);
        }
        StringBuilder origin = new StringBuilder()
            .append(uri.getScheme())
            .append("://")
            .append(uri.getHost());
        if (uri.getPort() != -1) {
            origin.append(":").append(uri.getPort());
        }
        return origin.toString();
    }

    public HttpResponse<String> minecraftGet(String path) throws Exception {
        return send(serverBuilder(path)
            .header("X-API-Key", apiKey)
            .GET()
            .build());
    }

    private HttpRequest.Builder serverBuilder(String path) {
        return newBuilder(path)
            .header("X-Server-Domain", serverDomain)
            .header("X-Forwarded-Host", serverDomain);
    }

    // ── Panel auth: X-Server-Domain + Cookie: MODL_SESSION=<token> ──

    private HttpRequest.Builder newBuilder(String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", "modl-backend-test-suite");
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 429) {
                return response;
            }
            // Rate limited — wait and retry
            Thread.sleep(1000L * (attempt + 1));
        }
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> minecraftPost(String path, Object body) throws Exception {
        return send(serverBuilder(path)
            .header("X-API-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body))
            .build());
    }

    private HttpRequest.BodyPublisher jsonBody(Object body) {
        if (body == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        String json = (body instanceof String s) ? s : gson.toJson(body);
        return HttpRequest.BodyPublishers.ofString(json);
    }

    public HttpResponse<String> minecraftPatch(String path, Object body) throws Exception {
        return send(serverBuilder(path)
            .header("X-API-Key", apiKey)
            .header("Content-Type", "application/json")
            .method("PATCH", jsonBody(body))
            .build());
    }

    // ── Public: X-Server-Domain only ──

    public HttpResponse<String> panelGet(String path) throws Exception {
        return send(panelBuilder(path).GET().build());
    }

    private HttpRequest.Builder panelBuilder(String path) {
        return serverBuilder(path)
            .header("Origin", panelOrigin)
            .header("Referer", panelOrigin + "/panel")
            .header("Cookie", "MODL_SESSION=" + sessionToken);
    }

    // ── Raw (no auth) ──

    public HttpResponse<String> panelPost(String path, Object body) throws Exception {
        return send(panelBuilder(path)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body))
            .build());
    }

    // ── Helpers ──

    public HttpResponse<String> panelPut(String path, Object body) throws Exception {
        return send(panelBuilder(path)
            .header("Content-Type", "application/json")
            .PUT(jsonBody(body))
            .build());
    }

    public HttpResponse<String> panelPatch(String path, Object body) throws Exception {
        return send(panelBuilder(path)
            .header("Content-Type", "application/json")
            .method("PATCH", jsonBody(body))
            .build());
    }

    public HttpResponse<String> panelDelete(String path) throws Exception {
        return send(panelBuilder(path).DELETE().build());
    }

    public HttpResponse<String> publicGet(String path) throws Exception {
        return send(serverBuilder(path)
            .GET()
            .build());
    }

    public HttpResponse<String> publicPost(String path, Object body) throws Exception {
        return send(serverBuilder(path)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body))
            .build());
    }

    public HttpResponse<String> rawGet(String path) throws Exception {
        return send(newBuilder(path).GET().build());
    }
}
