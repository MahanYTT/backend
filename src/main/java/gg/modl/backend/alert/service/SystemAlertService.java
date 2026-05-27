package gg.modl.backend.alert.service;

import gg.modl.backend.alert.data.SystemAlert;
import gg.modl.backend.alert.data.SystemAlertAudience;
import gg.modl.backend.alert.data.SystemAlertSeverity;
import gg.modl.backend.alert.dto.request.CreateSystemAlertRequest;
import gg.modl.backend.alert.dto.request.UpdateSystemAlertRequest;
import gg.modl.backend.database.mongo.repository.SystemAlertMongoRepository;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SystemAlertService {
    private final SystemAlertMongoRepository alertRepository;

    public List<SystemAlert> getAllAlerts() {
        return alertRepository.findAllOrdered();
    }

    public List<SystemAlert> getVisibleAlerts(boolean superAdmin, Date now) {
        return alertRepository.findVisible(now).stream()
            .filter(alert -> superAdmin || alert.getAudience() == SystemAlertAudience.ALL_PANEL_USERS)
            .toList();
    }

    public SystemAlert createAlert(CreateSystemAlertRequest request, String createdBy) {
        final Date now = new Date();
        final SystemAlert alert = SystemAlert.builder()
            .message(request.message().trim())
            .severity(request.severity() != null ? request.severity() : SystemAlertSeverity.BASIC)
            .audience(request.audience() != null ? request.audience() : SystemAlertAudience.ALL_PANEL_USERS)
            .expiresAt(request.expiresAt())
            .createdAt(now)
            .updatedAt(now)
            .createdBy(createdBy)
            .updatedBy(createdBy)
            .build();
        return alertRepository.saveEntity(alert);
    }

    public Optional<SystemAlert> updateAlert(String id, UpdateSystemAlertRequest request, String updatedBy) {
        final String message = request.message() != null ? request.message().trim() : null;
        if (message != null && message.isEmpty()) {
            throw new IllegalArgumentException("Alert message cannot be blank");
        }
        return alertRepository.updateAlert(
            id,
            message,
            request.severity(),
            request.audience(),
            request.expiresAt(),
            new Date(),
            updatedBy
        );
    }
}
