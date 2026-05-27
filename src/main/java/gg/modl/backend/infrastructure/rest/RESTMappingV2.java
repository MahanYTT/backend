package gg.modl.backend.infrastructure.rest;

public final class RESTMappingV2 {
    private static final String V2 = "/v2";
    public static final String PREFIX_MINECRAFT = V2 + "/minecraft";
    private static final String PLAYERS = "/players";
    public static final String MINECRAFT_PLAYERS = PREFIX_MINECRAFT + PLAYERS;

    private RESTMappingV2() {
        throw new UnsupportedOperationException();
    }
}
