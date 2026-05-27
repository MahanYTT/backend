package gg.modl.backend.settings.service;

import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.Settings;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ApiKeySettingsService {
    private final SettingsRepositoryAccess settingsRepositoryAccess;
    private final IdGenerator idGenerator;
    private final ServerMongoRepository serverRepository;
    private static final String SETTINGS_TYPE_API_KEYS = "apiKeys";
    private static final String API_KEY_FIELD = "api_key";

    @Nullable
    public Server findServerByApiKey(@NotNull String apiKey) {
        return serverRepository.findByApiKey(apiKey).orElse(null);
    }

    @Nullable
    public String getApiKeyFromSettings(@NotNull Server server) {
        Settings settings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_API_KEYS).orElse(null);
        if (settings == null || settings.getData() == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) settings.getData();
        Object apiKey = data.get(API_KEY_FIELD);
        return apiKey instanceof String ? (String) apiKey : null;
    }

    public void syncApiKeyToServer(@NotNull Server server, @NotNull String apiKey) {
        serverRepository.updateApiKey(server.getId(), apiKey);
    }

    public String generateApiKey(Server server, String keyType) {
        Settings settings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_API_KEYS).orElse(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = settings != null && settings.getData() != null
                                   ? new HashMap<>((Map<String, Object>) settings.getData())
                                   : new HashMap<>();

        String newApiKey = generateSecureApiKey();
        String fieldName = getFieldNameForType(keyType);

        data.put(fieldName, newApiKey);
        settingsRepositoryAccess.upsertSettings(server, SETTINGS_TYPE_API_KEYS, data);
        if (API_KEY_FIELD.equals(fieldName)) {
            syncApiKeyToServer(server, newApiKey);
            server.setApiKey(newApiKey);
        }

        return newApiKey;
    }

    private String generateSecureApiKey() {
        return "modl_" + idGenerator.generateToken();
    }

    private String getFieldNameForType(String keyType) {
        return switch (keyType.toLowerCase(Locale.ROOT)) {
            case "ticket" -> "ticket_api_key";
            case "minecraft" -> "minecraft_api_key";
            default -> "api_key";
        };
    }

    public boolean deleteApiKey(Server server, String keyType) {
        Settings settings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_API_KEYS).orElse(null);

        if (settings == null || settings.getData() == null) {
            return false;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = new HashMap<>((Map<String, Object>) settings.getData());
        String fieldName = getFieldNameForType(keyType);

        if (!data.containsKey(fieldName)) {
            return false;
        }

        data.remove(fieldName);
        settingsRepositoryAccess.upsertSettings(server, SETTINGS_TYPE_API_KEYS, data);
        if (API_KEY_FIELD.equals(fieldName)) {
            serverRepository.clearApiKey(server.getId());
            server.setApiKey(null);
        }

        return true;
    }

    public boolean hasApiKey(Server server, String keyType) {
        String apiKey = revealApiKey(server, keyType);
        return apiKey != null && !apiKey.isBlank();
    }

    public String revealApiKey(Server server, String keyType) {
        Settings settings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_API_KEYS).orElse(null);

        if (settings == null || settings.getData() == null) {
            return null;
        }

        String fieldName = getFieldNameForType(keyType);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) settings.getData();
        Object apiKey = data.get(fieldName);
        return apiKey instanceof String ? (String) apiKey : null;
    }
}
