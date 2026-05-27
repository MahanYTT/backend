package gg.modl.backend.settings.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.data.Settings;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiKeySettingsServiceTest {

    @Test
    void defaultGenerateSyncsCanonicalServerApiKey() {
        SettingsRepositoryAccess settingsRepositoryAccess = mock(SettingsRepositoryAccess.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        ApiKeySettingsService service = new ApiKeySettingsService(settingsRepositoryAccess, idGenerator, serverRepository);
        Server server = server();

        when(idGenerator.generateToken()).thenReturn("generated");

        String apiKey = service.generateApiKey(server, "default");

        assertEquals("modl_generated", apiKey);
        verify(serverRepository).updateApiKey(server.getId(), "modl_generated");
    }

    @Test
    void defaultDeleteClearsCanonicalServerApiKey() {
        SettingsRepositoryAccess settingsRepositoryAccess = mock(SettingsRepositoryAccess.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        ApiKeySettingsService service = new ApiKeySettingsService(settingsRepositoryAccess, idGenerator, serverRepository);
        Server server = server();
        Settings settings = new Settings();
        settings.setData(new HashMap<>(Map.of("api_key", "old")));
        when(settingsRepositoryAccess.findSettings(server, "apiKeys")).thenReturn(Optional.of(settings));

        boolean deleted = service.deleteApiKey(server, "default");

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(settingsRepositoryAccess).upsertSettings(org.mockito.ArgumentMatchers.eq(server), org.mockito.ArgumentMatchers.eq("apiKeys"), dataCaptor.capture());
        assertFalse(dataCaptor.getValue().containsKey("api_key"));
        verify(serverRepository).clearApiKey(server.getId());
        assertEquals(true, deleted);
    }

    @Test
    void invalidLookupDoesNotScanAllTenantSettings() {
        SettingsRepositoryAccess settingsRepositoryAccess = mock(SettingsRepositoryAccess.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        ApiKeySettingsService service = new ApiKeySettingsService(settingsRepositoryAccess, idGenerator, serverRepository);

        when(serverRepository.findByApiKey("invalid")).thenReturn(Optional.empty());

        assertNull(service.findServerByApiKey("invalid"));
        verify(serverRepository, never()).findAll();
        verify(settingsRepositoryAccess, never()).findSettings(org.mockito.ArgumentMatchers.any(), anyString());
    }

    private Server server() {
        Server server = new Server("Server", "server", "server_db", "admin@example.com", true, ServerPlan.FREE);
        server.setId("server-id");
        return server;
    }
}
