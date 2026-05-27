package gg.modl.backend.migration.validation;

import gg.modl.backend.infrastructure.validation.RegExpConstants;
import java.net.InetAddress;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MigrationValidator {
    private static final Pattern UUID_PATTERN = Pattern.compile(RegExpConstants.UUID);
    private static final Pattern UUID_NO_DASHES_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    public @NotNull ValidationResult validateMigrationData(@Nullable Map<String, Object> data) {
        if (data == null) {
            return ValidationResult.error("Migration data is null");
        }

        Object playersObj = data.get("players");
        if (playersObj == null) {
            return ValidationResult.error("Missing 'players' field");
        }

        if (!(playersObj instanceof List<?>)) {
            return ValidationResult.error("'players' field must be an array");
        }

        List<?> players = (List<?>) playersObj;

        if (players.isEmpty()) {
            return ValidationResult.error("Players array cannot be empty");
        }

        if (players.size() > 1_000_000) {
            return ValidationResult.error("Players array exceeds maximum length of 1,000,000");
        }

        int sampleSize = Math.min(100, players.size());
        for (int i = 0; i < sampleSize; i++) {
            int randomIndex = (int) (Math.random() * players.size());
            Object playerObj = players.get(randomIndex);

            if (!(playerObj instanceof Map<?, ?>)) {
                return ValidationResult.error("Invalid player object at index " + randomIndex);
            }

            Map<?, ?> player = (Map<?, ?>) playerObj;

            Object uuidObj = player.get("minecraftUuid");
            if (uuidObj == null || !(uuidObj instanceof String)) {
                return ValidationResult.error("Missing or invalid minecraftUuid at index " + randomIndex);
            }

            String uuid = (String) uuidObj;
            if (!isValidUuid(uuid)) {
                return ValidationResult.error("Invalid UUID format at index " + randomIndex + ": " + uuid);
            }

            Object usernamesObj = player.get("usernames");
            if (usernamesObj != null && !(usernamesObj instanceof List<?>)) {
                return ValidationResult.error("Invalid usernames field at index " + randomIndex);
            }

            Object punishmentsObj = player.get("punishments");
            if (punishmentsObj != null) {
                if (!(punishmentsObj instanceof List<?>)) {
                    return ValidationResult.error("Invalid punishments field at index " + randomIndex);
                }
                List<?> punishments = (List<?>) punishmentsObj;
                if (punishments.size() > 50000) {
                    return ValidationResult.error("Too many punishments at index " + randomIndex);
                }
            }
        }

        return ValidationResult.success(players.size());
    }

    public boolean isValidUuid(@Nullable String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return false;
        }

        return UUID_PATTERN.matcher(uuid).matches()
            || UUID_NO_DASHES_PATTERN.matcher(uuid).matches();
    }

    public @Nullable String normalizeUuid(@Nullable String uuid) {
        if (uuid == null) {
            return null;
        }

        String cleaned = uuid.replace("-", "").toLowerCase(Locale.ROOT);

        if (cleaned.length() != 32) {
            return uuid;
        }

        return cleaned.substring(0, 8) + "-" +
               cleaned.substring(8, 12) + "-" +
               cleaned.substring(12, 16) + "-" +
               cleaned.substring(16, 20) + "-" +
               cleaned.substring(20);
    }

    public @Nullable String sanitizeString(@Nullable String input, int maxLength) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();
        if (trimmed.length() > maxLength) {
            trimmed = trimmed.substring(0, maxLength);
        }

        return trimmed
            .replace("\u0000", "")
            .replace("\r", "");
    }

    public @Nullable Date parseDate(@Nullable Object dateObj) {
        if (dateObj == null) {
            return null;
        }

        if (dateObj instanceof Date) {
            return (Date) dateObj;
        }

        if (dateObj instanceof Number) {
            long timestamp = ((Number) dateObj).longValue();
            if (timestamp > 100_000_000_000L) {
                return new Date(timestamp);
            } else {
                return new Date(timestamp * 1000);
            }
        }

        if (dateObj instanceof String) {
            String dateStr = (String) dateObj;
            try {
                return Date.from(Instant.parse(dateStr));
            } catch (DateTimeParseException e) {
                try {
                    long timestamp = Long.parseLong(dateStr);
                    if (timestamp > 100_000_000_000L) {
                        return new Date(timestamp);
                    } else {
                        return new Date(timestamp * 1000);
                    }
                } catch (NumberFormatException ex) {
                    log.warn("Unable to parse date: {}", dateStr);
                    return null;
                }
            }
        }

        return null;
    }

    public boolean isValidIpAddress(@Nullable String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }

        try {
            InetAddress.getByName(ip);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public record ValidationResult(boolean valid, String error, int playerCount) {
        public static ValidationResult success(int playerCount) {
            return new ValidationResult(true, null, playerCount);
        }

        public static ValidationResult error(String error) {
            return new ValidationResult(false, error, 0);
        }
    }
}
