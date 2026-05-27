package gg.modl.backend.settings.service;

import gg.modl.backend.database.mongo.repository.SettingsMongoRepository;
import gg.modl.backend.infrastructure.util.MongoKeyUtils;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.Settings;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsDocumentService {
    private final SettingsMongoRepository settingsRepository;
    private static final long INITIAL_VERSION = 0L;

    public RawSettingsState saveRawState(Server server, String type, long expectedVersion, Map<String, Object> data) {
        Settings current = findLatestSettingsDocument(server, type);
        RawSettingsState currentState = toRawState(current);

        if (currentState.version() != expectedVersion) {
            throwConflict(currentState.version());
        }

        Map<String, Object> normalizedData = data == null
                                             ? new LinkedHashMap<>()
                                             : MongoKeyUtils.sanitizeKeys(data);
        Date now = new Date();

        if (!currentState.exists()) {
            try {
                Settings inserted = new Settings(null, type, normalizedData, expectedVersion + 1, now);
                settingsRepository.saveEntity(server, inserted);
                return new RawSettingsState(normalizedData, expectedVersion + 1, now, true);
            } catch (DuplicateKeyException duplicateKeyException) {
                throwConflict(getRawState(server, type).version());
            }
        }

        if (currentState.data().equals(normalizedData)) {
            return currentState;
        }

        boolean updated = settingsRepository.updateWithVersionCheck(
            server, current.getId(), expectedVersion, type, normalizedData, expectedVersion + 1, now);
        if (!updated) {
            throwConflict(getRawState(server, type).version());
        }

        return new RawSettingsState(normalizedData, expectedVersion + 1, now, true);
    }

    public RawSettingsState getRawState(Server server, String type) {
        Settings settings = findLatestSettingsDocument(server, type);
        return toRawState(settings);
    }

    private void throwConflict(long currentVersion) {
        throw new SettingsConflictException(
            "Settings were modified by another user. Reload and retry.",
            currentVersion
        );
    }

    private Settings findLatestSettingsDocument(Server server, String type) {
        List<Settings> matches = settingsRepository.findLatestByType(server, type, 2);
        if (matches.size() > 1) {
            log.warn(
                "Detected duplicate settings documents for type '{}'. Using latest id '{}'.",
                type,
                matches.get(0).getId()
            );
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    @SuppressWarnings("unchecked")
    private RawSettingsState toRawState(Settings settings) {
        if (settings == null) {
            return new RawSettingsState(new LinkedHashMap<>(), INITIAL_VERSION, null, false);
        }

        Map<String, Object> mappedData = new LinkedHashMap<>();
        if (settings.getData() instanceof Map<?, ?> rawMap) {
            mappedData.putAll((Map<String, Object>) rawMap);
        }

        long version = settings.getVersion() != null ? settings.getVersion() : INITIAL_VERSION;
        return new RawSettingsState(mappedData, version, settings.getUpdatedAt(), true);
    }

    public record RawSettingsState(
        Map<String, Object> data,
        long version,
        Date updatedAt,
        boolean exists
    ) {
    }
}
