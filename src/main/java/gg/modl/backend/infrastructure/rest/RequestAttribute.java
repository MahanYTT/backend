package gg.modl.backend.infrastructure.rest;

public final class RequestAttribute {
    public static final String SERVER = "gg.modl.attribute.server";
    public static final String SERVER_DOMAIN = "gg.modl.attribute.serverDomain";
    public static final String SESSION = "gg.modl.attribute.session";

    private RequestAttribute() {
        throw new UnsupportedOperationException();
    }
}
