package gg.modl.backend.admin.service;

import gg.modl.backend.admin.data.SystemConfig;
import gg.modl.backend.admin.data.SystemPrompt;
import gg.modl.backend.admin.dto.request.ToggleMaintenanceRequest;
import gg.modl.backend.admin.dto.request.UpdatePromptRequest;
import gg.modl.backend.admin.dto.request.UpdateRateLimitsRequest;
import gg.modl.backend.admin.dto.request.UpdateSystemConfigRequest;
import gg.modl.backend.ai.service.AITicketAnalysisService;
import gg.modl.backend.database.mongo.repository.SystemConfigMongoRepository;
import gg.modl.backend.database.mongo.repository.SystemPromptMongoRepository;
import java.util.Date;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GlobalSystemService {
    private final SystemConfigMongoRepository systemConfigRepository;
    private final SystemPromptMongoRepository systemPromptRepository;

    public @NotNull SystemConfig.GeneralConfig getGeneralConfigOrDefault() {
        return systemConfigRepository.findMainConfig()
            .map(SystemConfig::getGeneral)
            .orElseGet(SystemConfig.GeneralConfig::new);
    }

    public @NotNull SystemConfig updateConfig(@NotNull UpdateSystemConfigRequest request) {
        SystemConfig existing = getOrCreateConfig();
        request.applyTo(existing);
        existing.setUpdatedAt(new Date());
        return systemConfigRepository.saveEntity(existing);
    }

    public @NotNull SystemConfig getOrCreateConfig() {
        return systemConfigRepository.findMainConfig()
            .orElseGet(() -> systemConfigRepository.saveEntity(new SystemConfig()));
    }

    public Map<String, Object> getMaintenanceStatus() {
        SystemConfig config = getOrCreateConfig();
        return Map.of(
            "isActive", config.getGeneral().isMaintenanceMode(),
            "message", config.getGeneral().getMaintenanceMessage()
        );
    }

    public Map<String, Object> toggleMaintenance(ToggleMaintenanceRequest request) {
        SystemConfig config = getOrCreateConfig();
        config.getGeneral().setMaintenanceMode(request.enabled());
        if (request.message() != null) {
            config.getGeneral().setMaintenanceMessage(request.message());
        }
        config.setUpdatedAt(new Date());
        SystemConfig saved = systemConfigRepository.saveEntity(config);
        return Map.of(
            "isActive", saved.getGeneral().isMaintenanceMode(),
            "message", saved.getGeneral().getMaintenanceMessage()
        );
    }

    public Map<String, Object> getRateLimitStatus() {
        SystemConfig config = getOrCreateConfig();
        return Map.of(
            "current", config.getPerformance(),
            "active", true,
            "resetTime", new Date(System.currentTimeMillis() + 15 * 60 * 1000)
        );
    }

    public SystemConfig.PerformanceConfig updateRateLimits(UpdateRateLimitsRequest request) {
        SystemConfig config = getOrCreateConfig();

        if (request.rateLimitRequests() != null) {
            config.getPerformance().setRateLimitRequests(request.rateLimitRequests());
        }
        if (request.rateLimitWindow() != null) {
            config.getPerformance().setRateLimitWindow(request.rateLimitWindow());
        }
        config.setUpdatedAt(new Date());

        return systemConfigRepository.saveEntity(config).getPerformance();
    }

    public @Nullable SystemPrompt getPrompt() {
        return systemPromptRepository.findActive().orElse(null);
    }

    public @NotNull SystemPrompt updatePrompt(@NotNull UpdatePromptRequest request) {
        String prompt = request.prompt() != null ? request.prompt().trim() : "";
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("Prompt content is required");
        }

        return systemPromptRepository.upsertPrompt(prompt, new Date());
    }

    public @NotNull SystemPrompt resetPrompt() {
        return systemPromptRepository.upsertPrompt(AITicketAnalysisService.getDefaultPrompt(), new Date());
    }
}
