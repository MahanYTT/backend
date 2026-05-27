package gg.modl.backend.infrastructure.proto;

import com.google.protobuf.Message;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ProtoBinaryHttpMessageConverter extends AbstractHttpMessageConverter<Message> {

    public ProtoBinaryHttpMessageConverter() {
        super(ProtobufMediaTypes.APPLICATION_X_PROTOBUF, ProtobufMediaTypes.APPLICATION_PROTOBUF);
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
            Message defaultInstance = ProtoMessageReflection.getDefaultInstance(clazz);
            return defaultInstance.getParserForType().parseFrom(inputMessage.getBody());
        } catch (Exception e) {
            throw new HttpMessageNotReadableException("Failed to parse binary protobuf: " + e.getMessage(), e, inputMessage);
        }
    }

    @Override
    protected void writeInternal(@NonNull Message message,
                                 @NonNull HttpOutputMessage outputMessage) throws IOException {
        try {
            message.writeTo(outputMessage.getBody());
        } catch (Exception e) {
            throw new HttpMessageNotWritableException("Failed to write binary protobuf: " + e.getMessage(), e);
        }
    }
}
