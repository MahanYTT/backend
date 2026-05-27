package gg.modl.backend.storage.service;

import gg.modl.backend.infrastructure.util.ByteFormatUtil;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MediaValidationService {
    private static final long DEFAULT_MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MB

    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
        ".exe", ".bat", ".cmd", ".com", ".msi", ".scr", ".pif",
        ".js", ".vbs", ".wsf", ".jar", ".sh", ".ps1", ".php",
        ".asp", ".aspx", ".jsp", ".cgi", ".pl", ".py", ".rb"
    );

    private static final Map<String, Set<String>> ALLOWED_TYPES = Map.of(
        "evidence", Set.of("image/png", "image/jpeg", "image/gif", "image/apng", "image/webp", "video/mp4", "video/webm", "video/quicktime",
            "video/x-matroska", "application/pdf", "text/plain", "text/markdown"),
        "ticket", Set.of("image/png", "image/jpeg", "image/gif", "image/apng", "image/webp", "video/mp4", "video/webm", "video/quicktime",
            "video/x-matroska", "application/pdf", "text/plain", "text/markdown"),
        "appeal", Set.of("image/png", "image/jpeg", "image/gif", "image/apng", "image/webp", "video/mp4", "video/webm", "video/quicktime",
            "video/x-matroska", "application/pdf", "text/plain", "text/markdown"),
        "article", Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/apng", "text/plain", "text/markdown"),
        "server-icon", Set.of("image/png", "image/jpeg", "image/webp", "image/gif", "image/apng"),
        "replay", Set.of("application/octet-stream")
    );

    private static final Map<String, Long> MAX_SIZES = Map.of(
        "evidence", 100L * 1024 * 1024, // 100 mb
        "ticket", 100L * 1024 * 1024, // 100 mb
        "appeal", 100L * 1024 * 1024, // 100 mb
        "article", 50L * 1024 * 1024, // 50 mb
        "server-icon", 10L * 1024 * 1024, //  10 mb
        "replay", 10L * 1024 * 1024 // 10 mb
    );

    private static final Map<String, Long> PREMIUM_MAX_SIZES = Map.of(
        "evidence", 1L * 1024 * 1024 * 1024, // 1 GB
        "ticket", 1L * 1024 * 1024 * 1024, // 1 GB
        "appeal", 1L * 1024 * 1024 * 1024, // 1 GB
        "article", 50L * 1024 * 1024, // 50 mb
        "server-icon", 10L * 1024 * 1024, //  10 mb
        "replay", 10L * 1024 * 1024 // 10 mb
    );

    public ValidationResult validateMetadata(String fileName, String contentType, long fileSize, String uploadType) {
        return validateMetadata(fileName, contentType, fileSize, uploadType, false);
    }

    public ValidationResult validateMetadata(String fileName, String contentType, long fileSize, String uploadType, boolean isPremium) {
        if (fileName == null || fileName.isBlank()) {
            return new ValidationResult(false, "File name is required");
        }

        if (contentType == null || contentType.isBlank()) {
            return new ValidationResult(false, "Content type is required");
        }

        String lowerName = fileName.toLowerCase(Locale.ROOT);
        for (String ext : DANGEROUS_EXTENSIONS) {
            if (lowerName.endsWith(ext)) {
                log.warn("Blocked presign request for potentially dangerous file: {}", fileName);
                return new ValidationResult(false, "File type not allowed");
            }
        }

        Set<String> allowedTypes = getAllowedTypes(uploadType);
        if (allowedTypes.isEmpty()) {
            return new ValidationResult(false, "Invalid upload type");
        }

        if (!allowedTypes.contains(contentType)) {
            return new ValidationResult(false, "File type not allowed for " + uploadType + ". Allowed: " + allowedTypes);
        }

        long maxSize = getMaxSize(uploadType, isPremium);
        if (fileSize > maxSize) {
            return new ValidationResult(false, "File exceeds maximum size of " + ByteFormatUtil.formatCompact(maxSize));
        }

        if (fileSize <= 0) {
            return new ValidationResult(false, "Invalid file size");
        }

        return new ValidationResult(true, null);
    }

    public Set<String> getAllowedTypes(String uploadType) {
        return ALLOWED_TYPES.getOrDefault(uploadType, Set.of());
    }

    public long getMaxSize(String uploadType, boolean isPremium) {
        Map<String, Long> sizes = isPremium ? PREMIUM_MAX_SIZES : MAX_SIZES;
        return sizes.getOrDefault(uploadType, DEFAULT_MAX_FILE_SIZE);
    }

    public long getMaxSize(String uploadType) {
        return MAX_SIZES.getOrDefault(uploadType, DEFAULT_MAX_FILE_SIZE);
    }

    public Map<String, Object> getAllSupportedTypes() {
        return Map.of(
            "evidence", List.copyOf(ALLOWED_TYPES.get("evidence")),
            "tickets", List.copyOf(ALLOWED_TYPES.get("ticket")),
            "appeals", List.copyOf(ALLOWED_TYPES.get("appeal")),
            "articles", List.copyOf(ALLOWED_TYPES.get("article")),
            "server-icons", List.copyOf(ALLOWED_TYPES.get("server-icon")),
            "replays", List.copyOf(ALLOWED_TYPES.get("replay"))
        );
    }

    public Map<String, Object> getAllSizeLimits() {
        return getAllSizeLimits(false);
    }

    public Map<String, Object> getAllSizeLimits(boolean isPremium) {
        Map<String, Long> sizes = isPremium ? PREMIUM_MAX_SIZES : MAX_SIZES;
        return Map.of(
            "evidence", sizes.get("evidence"),
            "tickets", sizes.get("ticket"),
            "appeals", sizes.get("appeal"),
            "articles", sizes.get("article"),
            "server-icons", sizes.get("server-icon"),
            "replays", sizes.get("replay")
        );
    }

    public boolean isKeyOwnedByServer(String key, String serverDatabaseName) {
        return key != null && serverDatabaseName != null && key.startsWith(serverDatabaseName + "/");
    }

    public String extractUploadType(String key) {
        String[] parts = key.split("/");
        return parts.length >= 2 ? parts[1] : "";
    }

    public String extractEntityId(String key) {
        String[] parts = key.split("/");
        return parts.length >= 4 ? parts[2] : null;
    }

    public record ValidationResult(boolean valid, String error) {}
}
