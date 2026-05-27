package gg.modl.backend.infrastructure.rest;

public final class RESTSecurityRole {
    public static final String USER = "USER";
    public static final String ADMIN = "ADMIN";
    public static final String MINECRAFT = "MINECRAFT";
    public static final String ADMIN_SESSION_COOKIE = "modl.admin.session";

    private RESTSecurityRole() {
        throw new UnsupportedOperationException();
    }
}
