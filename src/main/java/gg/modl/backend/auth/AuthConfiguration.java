package gg.modl.backend.auth;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "modl.auth")
@Validated
@Getter
@Setter
public class AuthConfiguration {
    public static final long MIN_SESSION_DURATION_SECONDS = 14L * 24 * 60 * 60;

    private int emailCodeExpiry = 300;
    private int emailCodeLength = 6;
    @Min(MIN_SESSION_DURATION_SECONDS)
    private long sessionDurationSeconds = MIN_SESSION_DURATION_SECONDS;
    private String sessionCookieName = "MODL_SESSION";
    private String cookieDomain = "";
    private boolean cookieSecure = true;
    private boolean developmentMode = false;
    private String codeHashSecret = "";
}
