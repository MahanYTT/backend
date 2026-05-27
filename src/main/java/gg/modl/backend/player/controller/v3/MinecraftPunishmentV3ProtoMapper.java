package gg.modl.backend.player.controller.v3;

import com.google.protobuf.Struct;
import gg.modl.backend.player.controller.v1.MinecraftPunishmentController;
import gg.modl.backend.player.dto.response.PunishmentPreviewView;
import gg.modl.backend.player.dto.response.PunishmentSeverityPreviewView;
import gg.modl.proto.modl.v1.CreatePunishmentRequest;
import gg.modl.proto.modl.v1.PunishmentDetailResponse;
import gg.modl.proto.modl.v1.PunishmentEvidence;
import gg.modl.proto.modl.v1.PunishmentModification;
import gg.modl.proto.modl.v1.PunishmentNote;
import gg.modl.proto.modl.v1.PunishmentPreviewResponse;
import gg.modl.proto.modl.v1.RecentPunishmentsResponse;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

final class MinecraftPunishmentV3ProtoMapper {
    private MinecraftPunishmentV3ProtoMapper() {
    }

    static PunishmentPreviewResponse toProto(PunishmentPreviewView preview) {
        PunishmentPreviewResponse.Builder builder = PunishmentPreviewResponse.newBuilder()
            .setStatus(preview.getStatus())
            .setSuccess(preview.isSuccess())
            .setSingleSeverityPunishment(preview.isSingleSeverityPunishment())
            .setPermanentUntilUsernameChange(preview.isPermanentUntilUsernameChange())
            .setPermanentUntilSkinChange(preview.isPermanentUntilSkinChange())
            .setCanBeAltBlocking(preview.isCanBeAltBlocking())
            .setCanBeStatWiping(preview.isCanBeStatWiping())
            .setSocialPoints(preview.getSocialPoints())
            .setGameplayPoints(preview.getGameplayPoints());

        setIfNotNull(builder::setMessage, preview.getMessage());
        setIfNotNull(builder::setSocialStatus, preview.getSocialStatus());
        setIfNotNull(builder::setGameplayStatus, preview.getGameplayStatus());
        setIfNotNull(builder::setOffenderStatus, preview.getOffenderStatus());
        setIfNotNull(builder::setCategory, preview.getCategory());

        if (preview.getLenient() != null) {
            builder.setLenient(toProto(preview.getLenient()));
        }
        if (preview.getRegular() != null) {
            builder.setRegular(toProto(preview.getRegular()));
        }
        if (preview.getAggravated() != null) {
            builder.setAggravated(toProto(preview.getAggravated()));
        }
        if (preview.getSingleSeverity() != null) {
            builder.setSingleSeverity(toProto(preview.getSingleSeverity()));
        }

        return builder.build();
    }

    static PunishmentPreviewResponse.SeverityPreview toProto(PunishmentSeverityPreviewView preview) {
        PunishmentPreviewResponse.SeverityPreview.Builder builder =
            PunishmentPreviewResponse.SeverityPreview.newBuilder()
                .setPermanent(preview.isPermanent())
                .setPoints(preview.getPoints())
                .setDurationMs(preview.getDurationMs())
                .setNewSocialPoints(preview.getNewSocialPoints())
                .setNewGameplayPoints(preview.getNewGameplayPoints());

        setIfNotNull(builder::setSeverity, preview.getSeverity());
        setIfNotNull(builder::setDurationFormatted, preview.getDurationFormatted());
        setIfNotNull(builder::setPunishmentType, preview.getPunishmentType());
        setIfNotNull(builder::setNewSocialStatus, preview.getNewSocialStatus());
        setIfNotNull(builder::setNewGameplayStatus, preview.getNewGameplayStatus());

        return builder.build();
    }

    static PunishmentDetailResponse.PunishmentDetailEntry toPunishmentDetail(Map<String, Object> punishment) {
        PunishmentDetailResponse.PunishmentDetailEntry.Builder builder =
            PunishmentDetailResponse.PunishmentDetailEntry.newBuilder()
                .setPlayerName(stringValue(punishment.get("playerName")))
                .setPlayerUuid(stringValue(punishment.get("playerUuid")))
                .setId(stringValue(punishment.get("id")))
                .setIssuerName(stringValue(punishment.get("issuerName")))
                .setIssued(detailStringValue(punishment.get("issued")))
                .setStarted(detailStringValue(punishment.get("started")))
                .setType(stringValue(punishment.get("type")))
                .setTypeOrdinal(intValue(punishment.get("typeOrdinal")));

        list(punishment.get("attachedTicketIds")).stream()
            .map(Objects::toString)
            .forEach(builder::addAttachedTicketIds);

        Map<String, Object> data = mapValue(punishment.get("data"));
        if (data != null) {
            builder.setData(toDetailStruct(data));
        }
        listOfMaps(punishment.get("modifications")).stream()
            .map(MinecraftPunishmentV3ProtoMapper::toDetailStruct)
            .forEach(builder::addModifications);
        listOfMaps(punishment.get("notes")).stream()
            .map(MinecraftPunishmentV3ProtoMapper::toDetailStruct)
            .forEach(builder::addNotes);
        listOfMaps(punishment.get("evidence")).stream()
            .map(MinecraftPunishmentV3ProtoMapper::toDetailStruct)
            .forEach(builder::addEvidence);

        return builder.build();
    }

    static RecentPunishmentsResponse.RecentPunishment toRecentPunishment(Map<String, Object> punishment) {
        RecentPunishmentsResponse.RecentPunishment.Builder builder =
            RecentPunishmentsResponse.RecentPunishment.newBuilder()
                .setPlayerName(stringValue(punishment.get("playerName")))
                .setPlayerUuid(stringValue(punishment.get("playerUuid")))
                .setId(stringValue(punishment.get("id")))
                .setIssuerName(stringValue(punishment.get("issuerName")))
                .setIssued(longValue(punishment.get("issued")))
                .setType(stringValue(punishment.get("type")));

        setOptionalLong(builder::setStarted, punishment.get("started"));
        setOptionalInt(builder::setTypeOrdinal, punishment.get("typeOrdinal"));
        listOfMaps(punishment.get("modifications")).stream()
            .map(MinecraftPunishmentV3ProtoMapper::toPunishmentModification)
            .forEach(builder::addModifications);
        listOfMaps(punishment.get("notes")).stream()
            .map(MinecraftPunishmentV3ProtoMapper::toPunishmentNote)
            .forEach(builder::addNotes);
        listOfMaps(punishment.get("evidence")).stream()
            .map(MinecraftPunishmentV3ProtoMapper::toPunishmentEvidence)
            .forEach(builder::addEvidence);
        list(punishment.get("attachedTicketIds")).stream()
            .map(Objects::toString)
            .forEach(builder::addAttachedTicketIds);

        Map<String, Object> data = mapValue(punishment.get("data"));
        if (data != null) {
            builder.setData(MinecraftPlayerProtoMapper.toStruct(data));
        }

        return builder.build();
    }

    static MinecraftPunishmentController.MinecraftCreatePunishmentRequest toLegacyCreatePunishmentRequest(
        CreatePunishmentRequest request
    ) {
        return new MinecraftPunishmentController.MinecraftCreatePunishmentRequest(
            request.getTargetUuid(),
            request.hasIssuerName() ? request.getIssuerName() : null,
            request.hasIssuerId() ? request.getIssuerId() : null,
            request.getTypeOrdinal(),
            request.hasReason() ? request.getReason() : null,
            request.hasDuration() ? request.getDuration() : null,
            request.hasData() ? MinecraftPlayerProtoMapper.structToMap(request.getData()) : null,
            request.getNotesList(),
            request.getAttachedTicketIdsList(),
            request.hasSeverity() ? request.getSeverity() : null,
            request.hasStatus() ? request.getStatus() : null
        );
    }

    private static Struct toDetailStruct(Map<String, Object> map) {
        return MinecraftPlayerProtoMapper.toStruct(normalizeDetailMap(map));
    }

    private static Map<String, Object> normalizeDetailMap(Map<String, Object> map) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, value) -> normalized.put(key, normalizeDetailValue(value)));
        return normalized;
    }

    private static Object normalizeDetailValue(Object value) {
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) ->
                normalized.put(Objects.toString(key), normalizeDetailValue(nestedValue)));
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            iterable.forEach(item -> normalized.add(normalizeDetailValue(item)));
            return normalized;
        }
        return value;
    }

    private static String detailStringValue(Object value) {
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        return stringValue(value);
    }

    private static PunishmentModification toPunishmentModification(Map<String, Object> modification) {
        PunishmentModification.Builder builder = PunishmentModification.newBuilder()
            .setId(stringValue(modification.get("id")))
            .setType(stringValue(modification.get("type")))
            .setDate(longValue(modification.get("date")))
            .setReason(stringValue(modification.get("reason")));

        setOptionalString(builder::setIssuerName, modification.get("issuerName"));
        setOptionalString(builder::setIssuerId, modification.get("issuerId"));
        setOptionalLong(builder::setEffectiveDuration, modification.get("effectiveDuration"));
        setOptionalString(builder::setAppealTicketId, modification.get("appealTicketId"));
        Map<String, Object> data = mapValue(modification.get("data"));
        if (data != null) {
            builder.setData(MinecraftPlayerProtoMapper.toStruct(data));
        }

        return builder.build();
    }

    private static PunishmentNote toPunishmentNote(Map<String, Object> note) {
        PunishmentNote.Builder builder = PunishmentNote.newBuilder()
            .setId(stringValue(note.get("id")))
            .setText(stringValue(note.get("text")))
            .setDate(longValue(note.get("date")));

        setOptionalString(builder::setIssuerName, note.get("issuerName"));
        setOptionalString(builder::setIssuerId, note.get("issuerId"));
        return builder.build();
    }

    private static PunishmentEvidence toPunishmentEvidence(Map<String, Object> evidence) {
        PunishmentEvidence.Builder builder = PunishmentEvidence.newBuilder()
            .setType(stringValue(evidence.get("type")))
            .setUploadedAt(longValue(evidence.get("uploadedAt")));

        setOptionalString(builder::setText, evidence.get("text"));
        setOptionalString(builder::setUrl, evidence.get("url"));
        setOptionalString(builder::setUploadedBy, evidence.get("uploadedBy"));
        setOptionalString(builder::setUploadedById, evidence.get("uploadedById"));
        setOptionalString(builder::setFileName, evidence.get("fileName"));
        setOptionalString(builder::setFileType, evidence.get("fileType"));
        setOptionalLong(builder::setFileSize, evidence.get("fileSize"));
        return builder.build();
    }

    private static void setIfNotNull(Consumer<String> setter, String value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static void setOptionalString(Consumer<String> setter, Object value) {
        if (value != null) {
            setter.accept(Objects.toString(value));
        }
    }

    private static void setOptionalLong(LongConsumer setter, Object value) {
        if (value != null) {
            setter.accept(longValue(value));
        }
    }

    private static void setOptionalInt(IntConsumer setter, Object value) {
        if (value != null) {
            setter.accept(intValue(value));
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : Objects.toString(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Integer.parseInt(string);
        }
        return 0;
    }

    private static long longValue(Object value) {
        if (value instanceof Date date) {
            return date.getTime();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Long.parseLong(string);
        }
        return 0L;
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> values) {
            return values;
        }
        return List.of();
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        return list(value).stream()
            .filter(Map.class::isInstance)
            .map(item -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) item;
                return map;
            })
            .toList();
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        if (value instanceof Struct struct) {
            return MinecraftPlayerProtoMapper.structToMap(struct);
        }
        return null;
    }
}
