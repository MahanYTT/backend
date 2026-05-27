package gg.modl.backend.infrastructure.proto;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

@Component
public class ProtoJsonHttpMessageConverter extends AbstractHttpMessageConverter<Message> {

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();
    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

    public ProtoJsonHttpMessageConverter() {
        super(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
    }

    @Override
    protected boolean supports(@NonNull Class<?> clazz) {
        return Message.class.isAssignableFrom(clazz);
    }

    @Override
    @NonNull
    protected Message readInternal(@NonNull Class<? extends Message> clazz,
                                   @NonNull HttpInputMessage inputMessage) throws IOException {
        try {
            Message.Builder builder = ProtoMessageReflection.getDefaultInstance(clazz).newBuilderForType();
            try (InputStreamReader reader = new InputStreamReader(inputMessage.getBody(), StandardCharsets.UTF_8)) {
                PARSER.merge(reader, builder);
            }
            return builder.build();
        } catch (Exception e) {
            throw new HttpMessageNotReadableException("Failed to parse proto JSON: " + e.getMessage(), e, inputMessage);
        }
    }

    @Override
    protected void writeInternal(@NonNull Message message,
                                 @NonNull HttpOutputMessage outputMessage) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(outputMessage.getBody(), StandardCharsets.UTF_8)) {
            PRINTER.appendTo(message, writer);
        } catch (Exception e) {
            throw new HttpMessageNotWritableException("Failed to write proto JSON: " + e.getMessage(), e);
        }
    }
}
