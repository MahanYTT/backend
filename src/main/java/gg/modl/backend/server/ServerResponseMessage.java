package gg.modl.backend.server;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ServerResponseMessage {
    public static final String REGISTER_SUCCESS = "Registration successful. Please check your email to verify your account.";
    public static final String REGISTER_INVALID_SCHEMA = "Invalid registration data provided.";
    public static final String REGISTER_EMAIL_EXISTS = "The provided email is already in use!";
    public static final String REGISTER_DOMAIN_EXISTS = "The provided subdomain is already in use!";
    public static final String REGISTER_NAME_EXISTS = "The provided server name is already in use!";
    public static final String REGISTER_RESERVED_SUBDOMAIN = "The provided subdomain is reserved and cannot be used!";
}
