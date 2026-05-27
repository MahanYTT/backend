package gg.modl.backend.infrastructure.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DevelopmentModeGuard {
    private final Environment environment;
    private final ModlProperties modlProperties;
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "staging");

    @PostConstruct
    public void validate() {
        if (!modlProperties.isDevelopmentMode()) {
            return;
        }

        boolean isProductionProfile = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> PRODUCTION_PROFILES.contains(profile.toLowerCase(Locale.ROOT)));

        if (isProductionProfile) {
            throw new IllegalStateException(
                "FATAL: modl.development-mode=true is set with a production Spring profile active. " +
                "Development mode disables CSRF protection, captcha validation, and weakens cookie security. " +
                "Remove modl.development-mode or set it to false for production deployments."
            );
        }

        log.warn("======================================================================");
        log.warn("  DEVELOPMENT MODE IS ENABLED");
        log.warn("  CSRF protection, captcha validation, and cookie security are relaxed.");
        log.warn("  Do NOT use this setting in production.");
        log.warn("======================================================================");
    }
}
