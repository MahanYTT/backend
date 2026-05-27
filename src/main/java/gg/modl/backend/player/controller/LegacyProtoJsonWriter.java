package gg.modl.backend.player.controller;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class LegacyProtoJsonWriter {

    // Plugin clients (Gson) for v1/v2 expect default scalar values and empty arrays to be present
    // in the JSON body. Default JsonFormat output omits them, so use the legacy printer that
    // includes default-valued fields for these legacy endpoints.
    @SuppressWarnings("deprecation")
    private static final JsonFormat.Printer PRINTER = JsonFormat.printer()
        .includingDefaultValueFields()
        .omittingInsignificantWhitespace();

    private LegacyProtoJsonWriter() {
    }

    public static ResponseEntity<String> ok(MessageOrBuilder message) {
        try {
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(PRINTER.print(message));
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Failed to serialize legacy proto JSON response", e);
        }
    }
}
