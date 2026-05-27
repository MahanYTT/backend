package gg.modl.backend.infrastructure.util;

import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

@UtilityClass
public class UuidUtil {

    public static @Nullable String normalizeUuid(@Nullable String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
