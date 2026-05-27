package gg.modl.backend.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StagingCredentials {

    private static final String REQUIRE_CREDENTIALS_ENV = "MODL_E2E_REQUIRE_CREDENTIALS";

    private static final Map<String, String> PROPS = loadProperties();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static volatile ProbeResult publicApiProbe;
    private static volatile ProbeResult panelApiProbe;

    // Minimum credentials needed to drive the HTTP API. Mongo URI is required only when a
    // test calls TestDatabase — that path has its own TestDatabase.isAvailable() guard.
    private static final List<String> CORE_KEYS = List.of(
        "MODL_BASE_URL", "MODL_API_KEY", "MODL_SESSION_TOKEN", "MODL_SERVER_DOMAIN"
    );
    private static final List<String> ALL_REQUIRED_KEYS = List.of(
        "MODL_BASE_URL", "MODL_API_KEY", "MODL_SESSION_TOKEN", "MODL_SERVER_DOMAIN", "MODL_MONGO_URI"
    );

    static {
        if (Boolean.parseBoolean(System.getenv(REQUIRE_CREDENTIALS_ENV))) {
            List<String> missing = new ArrayList<>();
            for (String key : ALL_REQUIRED_KEYS) {
                if (get(key) == null) {
                    missing.add(key);
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                    "Staging credentials are required (" + REQUIRE_CREDENTIALS_ENV + "=true) but missing: "
                        + String.join(", ", missing));
            }
        }
    }

    private StagingCredentials() {}

    public static String baseUrl() {return get("MODL_BASE_URL");}

    public static String apiKey() {return get("MODL_API_KEY");}

    public static String sessionToken() {return get("MODL_SESSION_TOKEN");}

    public static String serverDomain() {return get("MODL_SERVER_DOMAIN");}

    public static String mongoUri() {return get("MODL_MONGO_URI");}

    private static String get(String key) {
        String val = PROPS.get(key);
        if (val != null) {
            return val;
        }
        return System.getenv(key);
    }

    public static String panelOrigin() {return get("MODL_PANEL_ORIGIN");}

    public static boolean isAvailable() {
        for (String key : CORE_KEYS) {
            if (get(key) == null) {
                return false;
            }
        }
        return true;
    }

    public static boolean isPublicApiAvailable() {
        return publicApiProbe().available();
    }

    public static String publicApiUnavailableReason() {
        return publicApiProbe().reason();
    }

    public static boolean isPanelApiAvailable() {
        return panelApiProbe().available();
    }

    public static String panelApiUnavailableReason() {
        return panelApiProbe().reason();
    }

    private static Map<String, String> loadProperties() {
        Map<String, String> map = new HashMap<>();
        Path envFile = Path.of("").toAbsolutePath().resolve(".env.test");
        if (!Files.exists(envFile)) {
            // Try one level up (in case tests run from backend/)
            envFile = envFile.getParent().resolve(".env.test");
        }
        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            } catch (IOException e) {
                // ignore – fall back to env vars
            }
        }
        return map;
    }

    private static ProbeResult publicApiProbe() {
        ProbeResult cached = publicApiProbe;
        if (cached != null) {
            return cached;
        }
        synchronized (StagingCredentials.class) {
            if (publicApiProbe == null) {
                publicApiProbe = probePublicApi();
            }
            return publicApiProbe;
        }
    }

    private static ProbeResult panelApiProbe() {
        ProbeResult cached = panelApiProbe;
        if (cached != null) {
            return cached;
        }
        synchronized (StagingCredentials.class) {
            if (panelApiProbe == null) {
                panelApiProbe = probePanelApi();
            }
            return panelApiProbe;
        }
    }

    private static ProbeResult probePublicApi() {
        if (!isAvailable()) {
            return new ProbeResult(false, "Staging credentials not configured");
        }

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/v1/public/settings"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "modl-backend-test-suite")
                    .header("X-Server-Domain", serverDomain())
                    .header("X-Forwarded-Host", serverDomain())
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new ProbeResult(true, "OK");
            }
            return new ProbeResult(false, "Public API probe returned HTTP " + response.statusCode());
        } catch (Exception exception) {
            return new ProbeResult(false, "Public API probe failed: " + exception.getClass().getSimpleName());
        }
    }

    private static ProbeResult probePanelApi() {
        if (!isAvailable()) {
            return new ProbeResult(false, "Staging credentials not configured");
        }

        try {
            String origin = resolveOrigin(panelOrigin() != null ? panelOrigin() : "https://admin.modl.gg");
            HttpResponse<String> response = HTTP_CLIENT.send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/v1/panel/auth/me"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "modl-backend-test-suite")
                    .header("X-Server-Domain", serverDomain())
                    .header("X-Forwarded-Host", serverDomain())
                    .header("Origin", origin)
                    .header("Referer", origin + "/panel")
                    .header("Cookie", "MODL_SESSION=" + sessionToken())
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new ProbeResult(true, "OK");
            }
            return new ProbeResult(false, "Panel API probe returned HTTP " + response.statusCode());
        } catch (Exception exception) {
            return new ProbeResult(false, "Panel API probe failed: " + exception.getClass().getSimpleName());
        }
    }

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

    private record ProbeResult(boolean available, String reason) {}
}
