package gg.modl.backend.infrastructure.util;

import gg.modl.backend.infrastructure.exception.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MongoKeyUtils {
    private MongoKeyUtils() {}

    /**
     * Validate map keys for MongoDB storage and recursively copy the value.
     * Rejecting invalid keys is intentional: lossy replacement can hide
     * collisions and still misses Mongo operator-looking fields.
     */
    public static Map<String, Object> sanitizeKeys(Map<String, Object> map) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (map == null) {
            return sanitized;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            validateKey(entry.getKey());
            sanitized.put(entry.getKey(), sanitizeValue(entry.getValue()));
        }
        return sanitized;
    }

    public static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new ValidationException("Mongo document keys must be strings");
                }
                validateKey(key);
                typed.put(key, sanitizeValue(entry.getValue()));
            }
            return typed;
        }
        if (value instanceof List<?> rawList) {
            List<Object> sanitized = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                sanitized.add(sanitizeValue(item));
            }
            return sanitized;
        }
        return value;
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.indexOf('\0') >= 0 || key.indexOf('.') >= 0 || key.startsWith("$")) {
            throw new ValidationException("Invalid Mongo document key");
        }
    }

    public static void validateUpdatePath(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0) {
            throw new ValidationException("Invalid Mongo update path");
        }
        for (String segment : path.split("\\.")) {
            validateKey(segment);
        }
    }

    /**
     * Resolve a field key to a human-readable label using the provided mapping.
     * Falls back to formatting the key directly if no label mapping exists.
     */
    public static String resolveFieldLabel(String key, Map<String, String> fieldLabels) {
        if (fieldLabels != null && fieldLabels.containsKey(key)) {
            return fieldLabels.get(key);
        }
        return formatFieldKey(key);
    }

    /**
     * Format a raw field key (e.g. "myField_name") into a human-readable label
     * (e.g. "My Field Name").
     */
    public static String formatFieldKey(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }

        String formatted = key.replace("_", " ");

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isWhitespace(formatted.charAt(i - 1))) {
                result.append(' ');
            }
            result.append(c);
        }
        formatted = result.toString();

        String[] words = formatted.split("\\s+");
        StringBuilder titleCase = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.isEmpty()) {
                titleCase.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    titleCase.append(word.substring(1).toLowerCase());
                }
                if (i < words.length - 1) {
                    titleCase.append(" ");
                }
            }
        }
        return titleCase.toString();
    }
}
