package gg.modl.backend.infrastructure.rest;

public final class RequestHeader {
    public static final String SERVER_DOMAIN = "X-Server-Domain";
    public static final String FORWARDED_HOST = "X-Forwarded-Host";
    public static final String API_KEY = "X-API-Key";

    private RequestHeader() {
        throw new UnsupportedOperationException();
    }
}
