package gg.modl.backend.settings.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.cloudflare.external.CloudflareClient;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.cors.DynamicCorsConfigurationSource;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DomainSettingsServiceTest {
    @Mock private SettingsRepositoryAccess settingsRepositoryAccess;
    @Mock private ServerMongoRepository serverRepository;
    @Mock private CloudflareClient cloudflareClient;
    @Mock private DynamicCorsConfigurationSource dynamicCorsConfigurationSource;
    @Mock private CustomDomainAccessService customDomainAccessService;
    private DomainSettingsService domainSettingsService;

    @BeforeEach
    void setUp() {
        domainSettingsService = new DomainSettingsService(
            settingsRepositoryAccess,
            serverRepository,
            cloudflareClient,
            dynamicCorsConfigurationSource,
            customDomainAccessService
        );
    }

    @Test
    void configureDomainRejectsPlatformDomainBeforeCloudflare() {
        assertThrows(ValidationException.class, () -> domainSettingsService.configureDomain(server(), "panel.modl.gg"));

        verify(cloudflareClient, never()).findCustomHostnameByName("panel.modl.gg");
    }

    @Test
    void configureDomainRejectsIpLiteralBeforeCloudflare() {
        assertThrows(ValidationException.class, () -> domainSettingsService.configureDomain(server(), "127.0.0.1"));

        verify(cloudflareClient, never()).findCustomHostnameByName("127.0.0.1");
    }

    @Test
    void configureDomainCanonicalizesBeforeCloudflare() {
        when(cloudflareClient.findCustomHostnameByName("example.org")).thenReturn(null);
        when(cloudflareClient.createCustomHostname("example.org"))
            .thenReturn(new CloudflareClient.CustomHostnameResult("cf-id", "example.org", "pending", null, null, null));

        domainSettingsService.configureDomain(server(), "Example.ORG");

        verify(cloudflareClient).findCustomHostnameByName("example.org");
        verify(cloudflareClient).createCustomHostname("example.org");
    }

    private static Server server() {
        Server server = new Server("server", "tenant", "db", "admin@example.com", true, ServerPlan.PREMIUM);
        server.setId("server-id");
        return server;
    }
}
