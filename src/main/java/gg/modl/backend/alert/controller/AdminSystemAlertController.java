package gg.modl.backend.alert.controller;

import gg.modl.backend.alert.data.SystemAlert;
import gg.modl.backend.alert.dto.request.CreateSystemAlertRequest;
import gg.modl.backend.alert.dto.request.UpdateSystemAlertRequest;
import gg.modl.backend.alert.dto.response.AdminSystemAlertResponse;
import gg.modl.backend.alert.service.SystemAlertService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.ADMIN_ALERTS)
@RequiredArgsConstructor
public class AdminSystemAlertController {
    private final SystemAlertService alertService;

    @GetMapping
    public ResponseEntity<?> getAlerts() {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", alertService.getAllAlerts().stream()
                .map(AdminSystemAlertResponse::from)
                .toList()
        ));
    }

    @PostMapping
    public ResponseEntity<?> createAlert(@RequestBody @Valid CreateSystemAlertRequest request) {
        final SystemAlert alert = alertService.createAlert(request, getAdminEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "success", true,
            "data", AdminSystemAlertResponse.from(alert),
            "message", "Alert created successfully"
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateAlert(
        @PathVariable String id,
        @RequestBody @Valid UpdateSystemAlertRequest request
    ) {
        return alertService.updateAlert(id, request, getAdminEmail())
            .<ResponseEntity<?>>map(alert -> ResponseEntity.ok(Map.of(
                "success", true,
                "data", AdminSystemAlertResponse.from(alert),
                "message", "Alert updated successfully"
            )))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "error", "Alert not found"
            )));
    }

    private String getAdminEmail() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return "unknown";
        }
        return authentication.getName();
    }
}
