package gg.modl.backend.settings.service;

import gg.modl.backend.cloudflare.external.CloudflareClient;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.infrastructure.cors.DynamicCorsConfigurationSource;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DomainSettings;
import gg.modl.backend.settings.data.Settings;
import java.net.IDN;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class DomainSettingsService {
    private final SettingsRepositoryAccess settingsRepositoryAccess;
    private final ServerMongoRepository serverRepository;
    private final CloudflareClient cloudflareClient;
    private final DynamicCorsConfigurationSource corsConfigurationSource;
    private final CustomDomainAccessService customDomainAccessService;
    private static final String SETTINGS_TYPE_DOMAIN = "domain";
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("^(?=.{1,253}$)(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))+$");
    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Set<String> RESERVED_SUFFIXES = Set.of(
        "modl.gg", "modl.top", "localhost", "local", "internal", "test", "example", "invalid"
    );

    public @NotNull DomainSettings getDomainSettings(@NotNull Server server, @Nullable String requestHost) {
        Settings settings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_DOMAIN).orElse(null);

        String modlSubdomainUrl = "https://" + server.getCustomDomain() + ".modl.gg";
        boolean canManageCustomDomain = customDomainAccessService.canManageCustomDomain(server);

        if (settings == null || settings.getData() == null) {
            return DomainSettings.builder()
                .customDomain(null)
                .status(null)
                .accessingFromCustomDomain(false)
                .modlSubdomainUrl(modlSubdomainUrl)
                .canManageCustomDomain(canManageCustomDomain)
                .build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) settings.getData();
        String customDomain = getStringValue(data, "customDomain");

        boolean accessingFromCustomDomain = customDomain != null && !customDomain.isEmpty()
                                            && requestHost != null && requestHost.equalsIgnoreCase(customDomain);

        DomainSettings.DomainStatus status = null;
        if (customDomain != null && !customDomain.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> statusData = (Map<String, Object>) data.get("status");
            if (statusData != null) {
                status = DomainSettings.DomainStatus.builder()
                    .domain(getStringValue(statusData, "domain"))
                    .status(getStringValue(statusData, "status"))
                    .cnameConfigured(getBooleanValue(statusData, "cnameConfigured"))
                    .sslStatus(getStringValue(statusData, "sslStatus"))
                    .lastChecked(getStringValue(statusData, "lastChecked"))
                    .error(getStringValue(statusData, "error"))
                    .build();
            }
        }

        return DomainSettings.builder()
            .customDomain(customDomain)
            .status(status)
            .accessingFromCustomDomain(accessingFromCustomDomain)
            .modlSubdomainUrl(modlSubdomainUrl)
            .canManageCustomDomain(canManageCustomDomain)
            .build();
    }

    private String getStringValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof String ? (String) value : null;
    }

    private boolean getBooleanValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof Boolean ? (Boolean) value : false;
    }

    public @NotNull DomainSettings configureDomain(@NotNull Server server, @NotNull String customDomain) {
        customDomain = normalizeAndValidateCustomDomain(customDomain);

        String currentDomain = extractCurrentDomain(server);

        if (currentDomain != null && currentDomain.equalsIgnoreCase(customDomain)) {
            throw new ConflictException("This domain is already configured. Please verify the existing configuration or remove it first.");
        }

        String currentCloudflareHostnameId = extractCurrentCloudflareHostnameId(server);
        CloudflareClient.CustomHostnameResult existingHostname = cloudflareClient.findCustomHostnameByName(customDomain);
        if (existingHostname != null) {
            if (currentCloudflareHostnameId == null || !existingHostname.id().equals(currentCloudflareHostnameId)) {
                throw new ConflictException("This domain is already configured by another server.");
            }
            cloudflareClient.deleteCustomHostname(existingHostname.id());
        }

        CloudflareClient.CustomHostnameResult cfResult = cloudflareClient.createCustomHostname(customDomain);

        String initialStatus = "pending";
        String sslStatus = "pending";
        String error = null;
        String cloudflareHostnameId = null;

        if (cfResult == null) {
            initialStatus = "error";
            sslStatus = "error";
            error = "Failed to create custom hostname in Cloudflare. Please check your configuration.";
            log.error("Failed to create Cloudflare custom hostname for domain: {}", customDomain);
        } else {
            cloudflareHostnameId = cfResult.id();
            initialStatus = mapCloudflareStatus(cfResult.status());
            if (cfResult.ssl() != null) {
                sslStatus = mapCloudflareStatus(cfResult.ssl().status());
            }
        }

        DomainSettings.DomainStatus status = DomainSettings.DomainStatus.builder()
            .domain(customDomain)
            .status(initialStatus)
            .cnameConfigured(false)
            .sslStatus(sslStatus)
            .lastChecked(Instant.now().toString())
            .error(error)
            .build();

        Map<String, Object> data = new HashMap<>();
        data.put("customDomain", customDomain);
        data.put("status", buildDomainStatusMap(status));
        data.put("cloudflareHostnameId", cloudflareHostnameId);

        settingsRepositoryAccess.upsertSettings(server, SETTINGS_TYPE_DOMAIN, data);

        updateServerDocument(server.getId(), customDomain, initialStatus, cloudflareHostnameId, error);

        return buildDomainSettingsResponse(server, customDomain, status);
    }

    private void updateServerDocument(String serverId, String customDomain, String status,
                                      String cloudflareHostnameId, String error) {
        serverRepository.updateCustomDomain(serverId, customDomain, status, cloudflareHostnameId, error);
        corsConfigurationSource.invalidateCache(customDomain);
        log.debug("Invalidated CORS cache for domain: {}", customDomain);
    }

    private String extractCurrentDomain(Server server) {
        String currentDomain = server.getCustomDomainOverride();
        if (currentDomain == null) {
            Settings existingSettings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_DOMAIN).orElse(null);
            if (existingSettings != null && existingSettings.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) existingSettings.getData();
                currentDomain = getStringValue(data, "customDomain");
            }
        }
        return currentDomain;
    }

    private String extractCurrentCloudflareHostnameId(Server server) {
        String currentCloudflareHostnameId = server.getCustomDomainCloudflareId();
        if (currentCloudflareHostnameId == null) {
            Settings existingSettings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_DOMAIN).orElse(null);
            if (existingSettings != null && existingSettings.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) existingSettings.getData();
                currentCloudflareHostnameId = getStringValue(data, "cloudflareHostnameId");
            }
        }
        return currentCloudflareHostnameId;
    }

    private Map<String, Object> buildDomainStatusMap(DomainSettings.DomainStatus status) {
        Map<String, Object> statusMap = new HashMap<>();
        statusMap.put("domain", status.getDomain());
        statusMap.put("status", status.getStatus());
        statusMap.put("cnameConfigured", status.isCnameConfigured());
        statusMap.put("sslStatus", status.getSslStatus());
        statusMap.put("lastChecked", status.getLastChecked());
        statusMap.put("error", status.getError());
        return statusMap;
    }

    private DomainSettings buildDomainSettingsResponse(Server server, String customDomain,
                                                       DomainSettings.DomainStatus status) {
        return DomainSettings.builder()
            .customDomain(customDomain)
            .status(status)
            .accessingFromCustomDomain(false)
            .modlSubdomainUrl("https://" + server.getCustomDomain() + ".modl.gg")
            .canManageCustomDomain(customDomainAccessService.canManageCustomDomain(server))
            .build();
    }

    private String mapCloudflareStatus(String cfStatus) {
        if (cfStatus == null) {
            return "pending";
        }
        return switch (cfStatus.toLowerCase(Locale.ROOT)) {
            case "active" -> "active";
            case "pending", "pending_validation", "pending_issuance", "pending_deployment", "initializing" -> "pending";
            case "pending_deletion", "deleted" -> "pending";
            case "blocked", "moved" -> "error";
            default -> "pending";
        };
    }

    public @NotNull DomainSettings verifyDomain(@NotNull Server server, @NotNull String domain) {
        domain = normalizeAndValidateCustomDomain(domain);
        Settings settings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_DOMAIN).orElse(null);

        if (settings == null || settings.getData() == null) {
            throw new ResourceNotFoundException("No domain configured");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) settings.getData();
        String configuredDomain = getStringValue(data, "customDomain");
        String cloudflareHostnameId = getStringValue(data, "cloudflareHostnameId");

        if (!domain.equalsIgnoreCase(configuredDomain)) {
            throw new ValidationException("Domain does not match configured domain");
        }

        String verifiedStatus = "pending";
        String sslStatus = "pending";
        boolean cnameConfigured = false;
        String error = null;

        CloudflareClient.CustomHostnameResult cfResult = null;

        if (cloudflareHostnameId != null && !cloudflareHostnameId.isEmpty()) {
            cfResult = cloudflareClient.getCustomHostname(cloudflareHostnameId);
        } else {
            cfResult = cloudflareClient.findCustomHostnameByName(domain);
            if (cfResult != null) {
                cloudflareHostnameId = cfResult.id();
            }
        }

        if (cfResult != null) {
            verifiedStatus = mapCloudflareStatus(cfResult.status());
            cnameConfigured = "active".equals(verifiedStatus);

            if (cfResult.ssl() != null) {
                sslStatus = mapCloudflareStatus(cfResult.ssl().status());
            }

            if ("blocked".equals(cfResult.status()) || "moved".equals(cfResult.status())) {
                error = "Domain verification failed. Status: " + cfResult.status();
            }
        } else {
            verifiedStatus = "error";
            sslStatus = "error";
            error = "Custom hostname not found in Cloudflare. Please reconfigure the domain.";
            log.warn("Custom hostname not found in Cloudflare for domain: {}", domain);
        }

        DomainSettings.DomainStatus status = DomainSettings.DomainStatus.builder()
            .domain(domain)
            .status(verifiedStatus)
            .cnameConfigured(cnameConfigured)
            .sslStatus(sslStatus)
            .lastChecked(Instant.now().toString())
            .error(error)
            .build();

        data.put("status", buildDomainStatusMap(status));
        if (cloudflareHostnameId != null) {
            data.put("cloudflareHostnameId", cloudflareHostnameId);
        }

        settingsRepositoryAccess.updateDataSettings(server, SETTINGS_TYPE_DOMAIN, data);

        updateServerDocument(server.getId(), domain, verifiedStatus, cloudflareHostnameId, error);

        return buildDomainSettingsResponse(server, domain, status);
    }

    private String normalizeAndValidateCustomDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new ValidationException("Custom domain is required");
        }
        String trimmed = domain.trim();
        if (trimmed.endsWith(".")) {
            throw new ValidationException("Custom domain must not include a trailing dot");
        }
        String ascii;
        try {
            ascii = IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("Invalid custom domain", exception);
        }
        if (!HOSTNAME_PATTERN.matcher(ascii).matches()
            || IPV4_PATTERN.matcher(ascii).matches()
            || ascii.contains(":")
            || isReservedDomain(ascii)) {
            throw new ValidationException("This custom domain cannot be used.");
        }
        return ascii;
    }

    private boolean isReservedDomain(String domain) {
        for (String suffix : RESERVED_SUFFIXES) {
            if (domain.equals(suffix) || domain.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    public void removeDomain(Server server) {
        Settings settings = settingsRepositoryAccess.findSettings(server, SETTINGS_TYPE_DOMAIN).orElse(null);

        String customDomain = null;

        if (settings != null && settings.getData() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) settings.getData();
            String cloudflareHostnameId = getStringValue(data, "cloudflareHostnameId");
            customDomain = getStringValue(data, "customDomain");

            if (cloudflareHostnameId != null && !cloudflareHostnameId.isEmpty()) {
                boolean deleted = cloudflareClient.deleteCustomHostname(cloudflareHostnameId);
                if (!deleted) {
                    log.warn("Failed to delete Cloudflare custom hostname for domain: {}", customDomain);
                }
            }
        }

        if (customDomain == null && server.getCustomDomainOverride() != null) {
            customDomain = server.getCustomDomainOverride();
        }

        settingsRepositoryAccess.removeSettings(server, SETTINGS_TYPE_DOMAIN);

        clearServerDomainFields(server.getId());

        if (customDomain != null && !customDomain.isEmpty()) {
            corsConfigurationSource.invalidateCache(customDomain);
            log.debug("Invalidated CORS cache for removed domain: {}", customDomain);
        }
    }

    private void clearServerDomainFields(String serverId) {
        serverRepository.clearCustomDomain(serverId);
    }
}
