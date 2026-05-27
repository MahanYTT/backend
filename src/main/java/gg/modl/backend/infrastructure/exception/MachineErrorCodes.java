package gg.modl.backend.infrastructure.exception;

import lombok.experimental.UtilityClass;
import org.springframework.http.HttpStatus;

@UtilityClass
public class MachineErrorCodes {

    public static String forStatus(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "INVALID_ARGUMENT";
            case UNAUTHORIZED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "PERMISSION_DENIED";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case NOT_ACCEPTABLE -> "NOT_ACCEPTABLE";
            case INTERNAL_SERVER_ERROR -> "INTERNAL";
            default -> status.is5xxServerError() ? "INTERNAL" : status.name();
        };
    }
}
