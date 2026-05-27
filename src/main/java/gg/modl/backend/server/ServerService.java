package gg.modl.backend.server;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.config.ModlCorsProperties;
import gg.modl.backend.server.data.ProvisioningStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.data.SubscriptionStatus;
import gg.modl.backend.server.service.ServerProvisioningService;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ServerService {
    private final ServerMongoRepository serverRepository;
    private final ServerProvisioningService provisioningService;
    private final Set<String> appDomains;
    public static final String SERVER_DATABASE_PREFIX = "server_";

    private final Cache<String, Optional<Server>> serverCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();

    public ServerService(
        ServerMongoRepository serverRepository,
        ServerProvisioningService provisioningService,
        ModlCorsProperties corsProperties
    ) {
        this.serverRepository = serverRepository;
        this.provisioningService = provisioningService;
        this.appDomains = Arrays.stream(corsProperties.getAppDomains().split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
    }

    @Async
    public void createServer(@NotNull Server server) {
        serverRepository.saveEntity(server);
        evictAllServerCaches();
    }

    public void createServer(@NotNull String serverName, @NotNull String customDomain, @NotNull String adminEmail) {
        createServer(serverName, customDomain, adminEmail, null, ServerPlan.FREE);
    }

    public Server createServer(@NotNull String serverName, @NotNull String customDomain, @NotNull String adminEmail,
                               @Nullable String emailVerificationToken, @NotNull ServerPlan plan) {
        Date now = new Date();
        String databaseName = generateDatabaseName(customDomain);

        Server server = new Server(serverName, customDomain, databaseName, adminEmail, false, plan);
        server.setProvisioningStatus(ProvisioningStatus.PENDING);
        server.setSubscriptionStatus(SubscriptionStatus.INACTIVE);
        server.setCreatedAt(now);
        server.setUpdatedAt(now);

        if (emailVerificationToken != null) {
            server.setEmailVerificationToken(emailVerificationToken);
        }

        Server saved = serverRepository.saveEntity(server);
        evictAllServerCaches();
        return saved;
    }

    public String generateDatabaseName(@NotNull String subdomain) {
        return SERVER_DATABASE_PREFIX + subdomain;
    }

    @Nullable
    public Server getServerFromDomain(@NotNull String domain) {
        return serverCache.get(domain, key -> {
            String subdomain = extractSubdomain(key);

            if (subdomain != null) {
                return serverRepository.findByCustomDomain(subdomain);
            }

            return serverRepository.findByActiveCustomDomainOverride(key);
        }).orElse(null);
    }

    public void evictServerCache(@NotNull String domain) {
        serverCache.invalidate(domain);
    }

    public void evictAllServerCaches() {
        serverCache.invalidateAll();
    }

    @Nullable
    private String extractSubdomain(@NotNull String domain) {
        for (String appDomain : appDomains) {
            String suffix = "." + appDomain;
            if (domain.endsWith(suffix)) {
                String subdomain = domain.substring(0, domain.length() - suffix.length());
                if (!subdomain.isBlank() && !subdomain.contains(".")) {
                    return subdomain;
                }
            }
        }
        return null;
    }

    /**
     * Returns the matching app domain (e.g. "modl.gg") if the given domain is a subdomain of one,
     * or null if it's a custom domain.
     */
    @Nullable
    public String getAppDomain(@NotNull String domain) {
        for (String appDomain : appDomains) {
            String suffix = "." + appDomain;
            if (domain.endsWith(suffix)) {
                String subdomain = domain.substring(0, domain.length() - suffix.length());
                if (!subdomain.isBlank() && !subdomain.contains(".")) {
                    return appDomain;
                }
            }
        }
        return null;
    }

    public ServerExistResult doesServerExist(@NotNull String email, @NotNull String serverName, @NotNull String subdomain) {
        Server found = serverRepository.findMatchingIdentity(email, serverName, subdomain).orElse(null);
        if (found == null) {
            return new ServerExistResult(false, false, false);
        }

        boolean emailMatch = false, nameMatch = false, domainMatch = false;

        if (found.getAdminEmail().equals(email)) {
            emailMatch = true;
        }

        if (found.getServerName().equals(serverName)) {
            nameMatch = true;
        }

        if (found.getCustomDomain().equals(subdomain)) {
            domainMatch = true;
        }

        return new ServerExistResult(emailMatch, nameMatch, domainMatch);
    }

    @Nullable
    public Server getServerByDatabaseName(@NotNull String databaseName) {
        return serverRepository.findByDatabaseName(databaseName).orElse(null);
    }

    @Nullable
    public Server getServerByApiKey(@NotNull String apiKey) {
        return serverRepository.findByApiKey(apiKey).orElse(null);
    }

    @Nullable
    public Server getServerByEmailVerificationToken(@NotNull String token) {
        return serverRepository.findByEmailVerificationToken(token).orElse(null);
    }

    @Nullable
    public Server verifyEmailToken(@NotNull String token) {
        Server server = serverRepository.verifyEmailTokenAtomically(token).orElse(null);

        if (server == null) {
            return null;
        }

        evictAllServerCaches();

        provisioningService.provision(server);

        return server;
    }

    @Nullable
    public Server getServerByAutoLoginToken(@NotNull String token) {
        return serverRepository.findByProvisioningSignInToken(token).orElse(null);
    }

    @Nullable
    public Server consumeAutoLoginToken(@NotNull String token) {
        Server server = serverRepository.consumeProvisioningSignInToken(token, new Date()).orElse(null);
        if (server != null) {
            evictAllServerCaches();
        }
        return server;
    }

    public Server setAutoLoginToken(@NotNull Server server, @NotNull String token, @NotNull Date expiresAt) {
        server.setProvisioningSignInToken(token);
        server.setProvisioningSignInTokenExpiresAt(expiresAt);
        server.setUpdatedAt(new Date());
        Server saved = serverRepository.saveEntity(server);
        evictAllServerCaches();
        return saved;
    }

    public Server clearAutoLoginToken(@NotNull Server server) {
        server.setProvisioningSignInToken(null);
        server.setProvisioningSignInTokenExpiresAt(null);
        server.setUpdatedAt(new Date());
        Server saved = serverRepository.saveEntity(server);
        evictAllServerCaches();
        return saved;
    }

    @Nullable
    public Server getServerByCliSetupToken(@NotNull String token) {
        return serverRepository.findByCliSetupToken(token).orElse(null);
    }

    public Server setCliSetupToken(@NotNull Server server, @NotNull String token) {
        server.setCliSetupToken(token);
        server.setUpdatedAt(new Date());
        Server saved = serverRepository.saveEntity(server);
        evictAllServerCaches();
        return saved;
    }

    public Server clearCliSetupToken(@NotNull Server server) {
        server.setCliSetupToken(null);
        server.setUpdatedAt(new Date());
        Server saved = serverRepository.saveEntity(server);
        evictAllServerCaches();
        return saved;
    }

    public record ServerExistResult(boolean emailMatch, boolean nameMatch, boolean domainMatch) {}
}
