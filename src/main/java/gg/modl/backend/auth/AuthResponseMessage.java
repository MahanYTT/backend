package gg.modl.backend.auth;

import lombok.experimental.UtilityClass;

@UtilityClass
public class AuthResponseMessage {
    public static final String MISSING_EMAIL = "Valid email is required.";
    public static final String UNAUTHORIZED_EMAIL = "This email is not authorized to access this panel.";
    public static final String VERIFICATION_CODE_SENT = "Verification code sent to your email.";
    public static final String MISSING_CODE = "Verification code is required.";
    public static final String INVALID_CODE = "Invalid or expired verification code.";
    public static final String LOGIN_SUCCESS = "Login successful.";
    public static final String LOGOUT_SUCCESS = "Logged out successfully.";
    public static final String EMAIL_SEND_ERROR = "Failed to send verification email. Please try again.";
}
