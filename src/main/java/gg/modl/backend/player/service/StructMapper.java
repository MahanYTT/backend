package gg.modl.backend.player.service;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.Map;
import java.util.Objects;

final class StructMapper {

    private StructMapper() {
    }

    static Struct toStruct(Map<?, ?> map) {
        Struct.Builder builder = Struct.newBuilder();
        if (map == null) {
            return builder.build();
        }
        map.forEach((key, value) -> builder.putFields(Objects.toString(key), objectToValue(value)));
        return builder.build();
    }

    private static Value objectToValue(Object object) {
        Value.Builder builder = Value.newBuilder();
        if (object == null) {
            return builder.setNullValue(NullValue.NULL_VALUE).build();
        }
        if (object instanceof String string) {
            return builder.setStringValue(string).build();
        }
        if (object instanceof Number number) {
            return builder.setNumberValue(number.doubleValue()).build();
        }
        if (object instanceof Boolean bool) {
            return builder.setBoolValue(bool).build();
        }
        if (object instanceof Map<?, ?> map) {
            Struct.Builder struct = Struct.newBuilder();
            map.forEach((key, value) -> struct.putFields(Objects.toString(key), objectToValue(value)));
            return builder.setStructValue(struct).build();
        }
        if (object instanceof Iterable<?> iterable) {
            ListValue.Builder list = ListValue.newBuilder();
            iterable.forEach(item -> list.addValues(objectToValue(item)));
            return builder.setListValue(list).build();
        }
        return builder.setStringValue(Objects.toString(object)).build();
    }
}
