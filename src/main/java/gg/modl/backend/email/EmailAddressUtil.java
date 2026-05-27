package gg.modl.backend.email;

import jakarta.mail.internet.InternetAddress;
import java.util.Locale;
import lombok.experimental.UtilityClass;

@UtilityClass
public class EmailAddressUtil {
    private static final int MAX_EMAIL_LENGTH = 254;

    public static String normalizeIfValid(String email) {
        String normalized = normalize(email);
        return isValid(normalized) ? normalized : null;
    }

    public static String normalize(String email) {
        if (email == null) {
            return null;
        }

        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public static boolean isValid(String email) {
        String normalized = normalize(email);
        if (normalized == null || normalized.length() > MAX_EMAIL_LENGTH) {
            return false;
        }

        try {
            InternetAddress address = new InternetAddress(normalized);
            address.validate();
            return normalized.equals(address.getAddress());
        } catch (Exception ex) {
            return false;
        }
    }

    public static String mask(String email) {
        String normalized = normalize(email);
        if (normalized == null) {
            return "<empty>";
        }

        int atIndex = normalized.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }

        return normalized.charAt(0) + "***" + normalized.substring(atIndex);
    }
}
