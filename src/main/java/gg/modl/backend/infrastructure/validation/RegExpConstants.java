package gg.modl.backend.infrastructure.validation;

public final class RegExpConstants {
    public static final String UUID = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    public static final String MINECRAFT_USERNAME = "^[a-zA-Z0-9_.]{2,16}$";
    // Matches IPv4 and IPv6 (including ::1, 0:0:0:0:0:0:0:1, and standard formats).
    public static final String IP = "^([0-9a-fA-F.:]+)$";

    private RegExpConstants() {
        throw new UnsupportedOperationException();
    }
}
