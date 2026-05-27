package gg.modl.backend.infrastructure.proto;

import com.google.protobuf.Message;
import lombok.experimental.UtilityClass;

@UtilityClass
class ProtoMessageReflection {

    static Message getDefaultInstance(Class<? extends Message> clazz) {
        try {
            return (Message) clazz.getMethod("getDefaultInstance").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot get default instance for " + clazz.getName(), e);
        }
    }
}
