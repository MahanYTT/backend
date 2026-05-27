package gg.modl.backend.settings.controller.v3;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.OffenseLevelDurations;
import gg.modl.backend.settings.data.PunishmentDurations;
import gg.modl.backend.settings.data.PunishmentPoints;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.proto.modl.v1.PunishmentTypesResponse;
import java.util.List;

final class MinecraftPunishmentTypeProtoMapper {
    private MinecraftPunishmentTypeProtoMapper() {
    }

    static PunishmentTypesResponse toPunishmentTypesResponse(int status, List<PunishmentType> types) {
        PunishmentTypesResponse.Builder response = PunishmentTypesResponse.newBuilder()
            .setStatus(status);
        types.stream()
            .map(MinecraftPunishmentTypeProtoMapper::toPunishmentTypeData)
            .forEach(response::addData);
        return response.build();
    }

    private static PunishmentTypesResponse.PunishmentTypeData toPunishmentTypeData(PunishmentType type) {
        PunishmentTypesResponse.PunishmentTypeData.Builder builder = PunishmentTypesResponse.PunishmentTypeData
            .newBuilder()
            .setName(stringValue(type.getName()))
            .setCategory(stringValue(type.getCategory()))
            .setStaffDescription(stringValue(type.getStaffDescription()))
            .setPlayerDescription(stringValue(type.getPlayerDescription()))
            .setDurations(toDurations(type.getDurations()))
            .setPoints(toPoints(type.getPoints()))
            .setId(intValue(type.getId()))
            .setOrdinal(intValue(type.getOrdinal()))
            .setIsCustomizable(type.isCustomizable());

        if (type.getCustomPoints() != null) {
            builder.setCustomPoints(type.getCustomPoints());
        }
        if (type.getCanBeAltBlocking() != null) {
            builder.setCanBeAltBlocking(type.getCanBeAltBlocking());
        }
        if (type.getCanBeStatWiping() != null) {
            builder.setCanBeStatWiping(type.getCanBeStatWiping());
        }
        if (type.getSingleSeverityPunishment() != null) {
            builder.setSingleSeverityPunishment(type.getSingleSeverityPunishment());
        }
        if (type.getPermanentUntilSkinChange() != null) {
            builder.setPermanentUntilSkinChange(type.getPermanentUntilSkinChange());
        }
        if (type.getPermanentUntilUsernameChange() != null) {
            builder.setPermanentUntilUsernameChange(type.getPermanentUntilUsernameChange());
        }

        return builder.build();
    }

    private static Struct toDurations(PunishmentDurations durations) {
        Struct.Builder builder = Struct.newBuilder();
        if (durations == null) {
            return builder.build();
        }
        putStruct(builder, "low", toOffenseLevelDurations(durations.low()));
        putStruct(builder, "regular", toOffenseLevelDurations(durations.regular()));
        putStruct(builder, "severe", toOffenseLevelDurations(durations.severe()));
        return builder.build();
    }

    private static Struct toOffenseLevelDurations(OffenseLevelDurations durations) {
        Struct.Builder builder = Struct.newBuilder();
        if (durations == null) {
            return builder.build();
        }
        putStruct(builder, "first", toDurationDetail(durations.first()));
        putStruct(builder, "medium", toDurationDetail(durations.medium()));
        putStruct(builder, "habitual", toDurationDetail(durations.habitual()));
        return builder.build();
    }

    private static Struct toDurationDetail(DurationDetail detail) {
        Struct.Builder builder = Struct.newBuilder();
        if (detail == null) {
            return builder.build();
        }
        putNumber(builder, "value", detail.value());
        putString(builder, "unit", detail.unit());
        putString(builder, "type", detail.type());
        return builder.build();
    }

    private static Struct toPoints(PunishmentPoints points) {
        Struct.Builder builder = Struct.newBuilder();
        if (points == null) {
            return builder.build();
        }
        putNumber(builder, "low", points.low());
        putNumber(builder, "regular", points.regular());
        putNumber(builder, "severe", points.severe());
        return builder.build();
    }

    private static void putStruct(Struct.Builder builder, String key, Struct value) {
        builder.putFields(key, Value.newBuilder().setStructValue(value).build());
    }

    private static void putNumber(Struct.Builder builder, String key, int value) {
        builder.putFields(key, Value.newBuilder().setNumberValue(value).build());
    }

    private static void putString(Struct.Builder builder, String key, String value) {
        builder.putFields(key, Value.newBuilder().setStringValue(stringValue(value)).build());
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private static String stringValue(String value) {
        return value == null ? "" : value;
    }
}
