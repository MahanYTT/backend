package gg.modl.backend.player.controller.v3;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import gg.modl.proto.modl.v1.Account;
import gg.modl.proto.modl.v1.IPEntry;
import gg.modl.proto.modl.v1.LinkedAccountsResponse;
import gg.modl.proto.modl.v1.NoteEntry;
import gg.modl.proto.modl.v1.OnlinePlayersResponse;
import gg.modl.proto.modl.v1.PaginatedNotesResponse;
import gg.modl.proto.modl.v1.PaginatedPunishmentsResponse;
import gg.modl.proto.modl.v1.PardonResponse;
import gg.modl.proto.modl.v1.PendingStatWipe;
import gg.modl.proto.modl.v1.PlayerGetResponse;
import gg.modl.proto.modl.v1.PlayerLoginResponse;
import gg.modl.proto.modl.v1.PlayerLookupResponse;
import gg.modl.proto.modl.v1.PlayerNameResponse;
import gg.modl.proto.modl.v1.PlayerNoteCreateResponse;
import gg.modl.proto.modl.v1.PlayerProfileResponse;
import gg.modl.proto.modl.v1.PunishmentEvidence;
import gg.modl.proto.modl.v1.PunishmentListEntry;
import gg.modl.proto.modl.v1.PunishmentModification;
import gg.modl.proto.modl.v1.PunishmentNote;
import gg.modl.proto.modl.v1.PunishmentResponse;
import gg.modl.proto.modl.v1.ReportEntry;
import gg.modl.proto.modl.v1.ReportsResponse;
import gg.modl.proto.modl.v1.SimpleResponse;
import gg.modl.proto.modl.v1.SimplePunishment;
import gg.modl.proto.modl.v1.UsernameEntry;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MinecraftPlayerProtoMapper {
    private static final Logger log = LoggerFactory.getLogger(MinecraftPlayerProtoMapper.class);

    private MinecraftPlayerProtoMapper() {
    }

    static PlayerLoginResponse toPlayerLoginResponse(Map<String, Object> body) {
        PlayerLoginResponse.Builder response = PlayerLoginResponse.newBuilder()
            .setStatus(intValue(body.get("status")));

        listOfMaps(body.get("activePunishments")).stream()
            .map(MinecraftPlayerProtoMapper::toSimplePunishment)
            .forEach(response::addActivePunishments);

        listOfMaps(body.get("pendingNotifications")).stream()
            .map(MinecraftPlayerProtoMapper::toStruct)
            .forEach(response::addPendingNotifications);

        list(body.get("pendingIpLookups")).stream()
            .map(Objects::toString)
            .forEach(response::addPendingIpLookups);

        listOfMaps(body.get("pendingStatWipes")).stream()
            .map(MinecraftPlayerProtoMapper::toPendingStatWipe)
            .forEach(response::addPendingStatWipes);

        return response.build();
    }

    static Map<String, Object> structToMap(Struct struct) {
        Map<String, Object> result = new LinkedHashMap<>();
        struct.getFieldsMap().forEach((key, value) -> result.put(key, valueToObject(value)));
        return result;
    }

    static SimpleResponse toSimpleResponse(Map<String, Object> body) {
        return SimpleResponse.newBuilder()
            .setSuccess(booleanValue(body.get("success")))
            .build();
    }

    static OnlinePlayersResponse toOnlinePlayersResponse(Map<String, Object> body) {
        OnlinePlayersResponse.Builder response = OnlinePlayersResponse.newBuilder()
            .setStatus(intValue(body.get("status")));

        listOfMaps(body.get("players")).stream()
            .map(MinecraftPlayerProtoMapper::toOnlinePlayer)
            .forEach(response::addPlayers);

        return response.build();
    }

    static PlayerProfileResponse toPlayerProfileResponse(Map<String, Object> body) {
        Map<String, Object> profile = map(body.get("profile"));
        PlayerProfileResponse.Builder response = PlayerProfileResponse.newBuilder()
            .setStatus(intValue(body.get("status")))
            .setProfile(toAccount(profile));

        setOptionalInt(response::setPunishmentCount, nestedOrTopLevel(profile, body, "punishmentCount"));
        setOptionalInt(response::setNoteCount, nestedOrTopLevel(profile, body, "noteCount"));
        return response.build();
    }

    static PlayerGetResponse toPlayerGetResponse(Map<String, Object> body) {
        return PlayerGetResponse.newBuilder()
            .setStatus(intValue(body.get("status")))
            .setMessage(stringValue(body.get("message")))
            .setPlayer(toAccount(map(body.get("player"))))
            .build();
    }

    static PlayerNameResponse toPlayerNameResponse(Map<String, Object> body) {
        return PlayerNameResponse.newBuilder()
            .setStatus(intValue(body.get("status")))
            .setMessage(stringValue(body.get("message")))
            .setPlayer(toAccount(map(body.get("player"))))
            .build();
    }

    static PlayerLookupResponse toPlayerLookupResponse(Map<String, Object> body) {
        return PlayerLookupResponse.newBuilder()
            .setStatus(intValue(body.get("status")))
            .setMessage(stringValue(body.get("message")))
            .setData(toPlayerLookupData(map(body.get("data"))))
            .build();
    }

    static PlayerNoteCreateResponse toPlayerNoteCreateResponse(Map<String, Object> body) {
        PlayerNoteCreateResponse.Builder builder = PlayerNoteCreateResponse.newBuilder()
            .setStatus(intValue(body.get("status")))
            .setMessage(stringValue(body.get("message")));
        setBooleanIfPresent(builder, "setSuccess", body.get("success"));
        return builder.build();
    }

    static LinkedAccountsResponse toLinkedAccountsResponse(Map<String, Object> body) {
        LinkedAccountsResponse.Builder response = LinkedAccountsResponse.newBuilder()
            .setStatus(intValue(body.get("status")))
            .setPage(intValue(body.get("page")))
            .setHasMore(booleanValue(body.get("hasMore")));

        setOptionalInt(response::setTotalCount, body.get("totalCount"));
        listOfMaps(body.get("linkedAccounts")).stream()
            .map(MinecraftPlayerProtoMapper::toAccount)
            .forEach(response::addLinkedAccounts);

        return response.build();
    }

    static PaginatedPunishmentsResponse toPaginatedPunishmentsResponse(Map<String, Object> body) {
        PaginatedPunishmentsResponse.Builder response = PaginatedPunishmentsResponse.newBuilder()
            .setStatus(intValue(body.get("status")))
            .setTotalCount(intValue(body.get("totalCount")))
            .setPage(intValue(body.get("page")))
            .setHasMore(booleanValue(body.get("hasMore")));

        listOfMaps(body.get("punishments")).stream()
            .map(MinecraftPlayerProtoMapper::toPunishmentListEntry)
            .forEach(response::addPunishments);

        return response.build();
    }

    static PaginatedNotesResponse toPaginatedNotesResponse(Map<String, Object> body) {
        PaginatedNotesResponse.Builder response = PaginatedNotesResponse.newBuilder()
            .setStatus(intValue(body.get("status")))
            .setTotalCount(intValue(body.get("totalCount")))
            .setPage(intValue(body.get("page")))
            .setHasMore(booleanValue(body.get("hasMore")));

        listOfMaps(body.get("notes")).stream()
            .map(MinecraftPlayerProtoMapper::toNoteEntry)
            .forEach(response::addNotes);

        return response.build();
    }

    static ReportsResponse toReportsResponse(Map<String, Object> body) {
        ReportsResponse.Builder response = ReportsResponse.newBuilder()
            .setStatus(intValue(body.get("status")));

        listOfMaps(body.get("reports")).stream()
            .map(MinecraftPlayerProtoMapper::toReportEntry)
            .forEach(response::addReports);

        return response.build();
    }

    static PardonResponse toPardonResponse(Map<String, Object> body) {
        return PardonResponse.newBuilder()
            .setStatus(intValue(body.get("status")))
            .setSuccess(booleanValue(body.get("success")))
            .setPardonedCount(intValue(body.get("pardonedCount")))
            .setMessage(stringValue(body.get("message")))
            .build();
    }

    static SimplePunishment toSimplePunishment(Map<String, Object> punishment) {
        SimplePunishment.Builder builder = SimplePunishment.newBuilder()
            .setType(stringValue(punishment.get("type")))
            .setDescription(stringValue(punishment.get("description")))
            .setId(stringValue(punishment.get("id")))
            .setStarted(booleanValue(punishment.get("started")))
            .setOrdinal(intValue(punishment.get("ordinal")));

        setOptionalString(builder::setCategory, punishment.get("category"));
        setOptionalLong(builder::setExpiration, punishment.get("expiration"));
        setOptionalString(builder::setIssuerName, punishment.get("issuerName"));
        setOptionalLong(builder::setIssuedAt, punishment.get("issuedAt"));
        setOptionalString(builder::setPlayerDescription, punishment.get("playerDescription"));
        return builder.build();
    }

    private static PendingStatWipe toPendingStatWipe(Map<String, Object> wipe) {
        return PendingStatWipe.newBuilder()
            .setMinecraftUuid(stringValue(wipe.get("minecraftUuid")))
            .setUsername(stringValue(wipe.get("username")))
            .setPunishmentId(stringValue(wipe.get("punishmentId")))
            .build();
    }

    private static OnlinePlayersResponse.OnlinePlayer toOnlinePlayer(Map<String, Object> player) {
        OnlinePlayersResponse.OnlinePlayer.Builder builder = OnlinePlayersResponse.OnlinePlayer.newBuilder()
            .setUuid(stringValue(player.get("uuid")))
            .setUsername(stringValue(player.get("username")))
            .setJoinedAt(stringValue(player.get("joinedAt")));

        setOptionalLong(builder::setTotalPlaytimeMs, player.get("totalPlaytimeMs"));
        return builder.build();
    }

    private static Account toAccount(Map<String, Object> account) {
        Account.Builder builder = Account.newBuilder()
            .setId(stringValue(account.get("id")))
            .setMinecraftUuid(stringValue(account.get("minecraftUuid")));

        listOfMaps(account.get("usernames")).stream()
            .map(MinecraftPlayerProtoMapper::toUsernameEntry)
            .forEach(builder::addUsernames);

        listOfMaps(account.get("notes")).stream()
            .map(MinecraftPlayerProtoMapper::toNoteEntry)
            .forEach(builder::addNotes);

        listOfMaps(account.get("ipAddresses")).stream()
            .map(MinecraftPlayerProtoMapper::toIpEntry)
            .forEach(builder::addIpAddresses);

        listOfMaps(account.get("punishments")).stream()
            .map(MinecraftPlayerProtoMapper::toPunishmentResponse)
            .forEach(builder::addPunishments);

        listOfMaps(account.get("pendingNotifications")).stream()
            .map(MinecraftPlayerProtoMapper::toStruct)
            .forEach(builder::addPendingNotifications);

        Object data = account.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            builder.setData(toStruct(stringObjectMap(dataMap)));
        }

        return builder.build();
    }

    private static UsernameEntry toUsernameEntry(Map<String, Object> username) {
        return UsernameEntry.newBuilder()
            .setUsername(stringValue(username.get("username")))
            .setDate(stringValue(username.get("date")))
            .build();
    }

    private static NoteEntry toNoteEntry(Map<String, Object> note) {
        NoteEntry.Builder builder = NoteEntry.newBuilder()
            .setText(stringValue(note.get("text")))
            .setDate(stringValue(note.get("date")))
            .setIssuerName(stringValue(note.get("issuerName")))
            .setIssuerId(stringValue(note.get("issuerId")));
        setStringIfPresent(builder, "setId", note.get("id"));
        return builder.build();
    }

    private static IPEntry toIpEntry(Map<String, Object> ip) {
        IPEntry.Builder builder = IPEntry.newBuilder()
            .setProxy(booleanValue(ip.get("proxy")))
            .setHosting(booleanValue(ip.get("hosting")))
            .setFirstLogin(stringValue(ip.get("firstLogin")));

        setOptionalString(builder::setIpAddress, ip.get("ipAddress"));
        setOptionalString(builder::setCountry, ip.get("country"));
        setOptionalString(builder::setRegion, ip.get("region"));
        setOptionalString(builder::setAsn, ip.get("asn"));

        list(ip.get("logins")).stream()
            .map(Objects::toString)
            .forEach(builder::addLogins);

        return builder.build();
    }

    private static PunishmentResponse toPunishmentResponse(Map<String, Object> punishment) {
        PunishmentResponse.Builder builder = PunishmentResponse.newBuilder()
            .setId(stringValue(punishment.get("id")))
            .setType(stringValue(punishment.get("type")))
            .setTypeOrdinal(intValue(punishment.get("typeOrdinal")))
            .setIssuerName(stringValue(punishment.get("issuerName")))
            .setIssued(longValue(punishment.get("issued")))
            .setIsAppealable(booleanValue(punishment.get("isAppealable")))
            .setActive(booleanValue(punishment.get("active")));

        setOptionalLong(builder::setStarted, punishment.get("started"));
        setOptionalString(builder::setReason, punishment.get("reason"));
        setOptionalString(builder::setSeverity, punishment.get("severity"));
        setOptionalString(builder::setStatus, punishment.get("status"));
        setOptionalLong(builder::setExpires, punishment.get("expires"));
        setOptionalString(builder::setPlayerUuid, punishment.get("playerUuid"));
        setOptionalString(builder::setPlayerUsername, punishment.get("playerUsername"));
        setOptionalBoolean(builder::setAltBlocking, punishment.get("altBlocking"));
        setOptionalBoolean(builder::setStatWiping, punishment.get("statWiping"));
        setOptionalString(builder::setEffectiveCategory, punishment.get("effectiveCategory"));

        list(punishment.get("attachedTicketIds")).stream()
            .map(Objects::toString)
            .forEach(builder::addAttachedTicketIds);

        return builder.build();
    }

    private static PunishmentListEntry toPunishmentListEntry(Map<String, Object> punishment) {
        PunishmentListEntry.Builder builder = PunishmentListEntry.newBuilder()
            .setId(stringValue(punishment.get("id")))
            .setIssuerName(stringValue(punishment.get("issuerName")))
            .setIssued(longValue(punishment.get("issued")))
            .setType(stringValue(punishment.get("type")));

        setOptionalLong(builder::setStarted, punishment.get("started"));
        setOptionalInt(builder::setTypeOrdinal, punishment.get("typeOrdinal"));

        listOfMaps(punishment.get("modifications")).stream()
            .map(MinecraftPlayerProtoMapper::toPunishmentModification)
            .forEach(builder::addModifications);

        listOfMaps(punishment.get("notes")).stream()
            .map(MinecraftPlayerProtoMapper::toPunishmentNote)
            .forEach(builder::addNotes);

        listOfMaps(punishment.get("evidence")).stream()
            .map(MinecraftPlayerProtoMapper::toPunishmentEvidence)
            .forEach(builder::addEvidence);

        list(punishment.get("attachedTicketIds")).stream()
            .map(Objects::toString)
            .forEach(builder::addAttachedTicketIds);

        Object data = punishment.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            builder.setData(toStruct(stringObjectMap(dataMap)));
        }

        return builder.build();
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
        Object data = modification.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            builder.setData(toStruct(stringObjectMap(dataMap)));
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

    private static ReportEntry toReportEntry(Map<String, Object> report) {
        ReportEntry.Builder builder = ReportEntry.newBuilder()
            .setId(stringValue(report.get("id")))
            .setType(stringValue(report.get("type")))
            .setCategory(stringValue(report.get("category")))
            .setReporterName(stringValue(report.get("reporterName")))
            .setReporterUuid(stringValue(report.get("reporterUuid")))
            .setReportedPlayerUuid(stringValue(report.get("reportedPlayerUuid")))
            .setReportedPlayerName(stringValue(report.get("reportedPlayerName")))
            .setSubject(stringValue(report.get("subject")))
            .setContent(stringValue(report.get("content")))
            .setStatus(stringValue(report.get("status")))
            .setPriority(stringValue(report.get("priority")))
            .setCreatedAt(longValue(report.get("createdAt")));

        list(report.get("assignedTo")).stream()
            .map(Objects::toString)
            .forEach(builder::addAssignedTo);

        listOfMaps(report.get("chatMessages")).stream()
            .map(MinecraftPlayerProtoMapper::toStruct)
            .forEach(builder::addChatMessages);

        setOptionalString(builder::setReplayUrl, report.get("replayUrl"));
        return builder.build();
    }

    private static PlayerLookupResponse.PlayerLookupData toPlayerLookupData(Map<String, Object> data) {
        PlayerLookupResponse.PlayerLookupData.Builder builder = PlayerLookupResponse.PlayerLookupData.newBuilder()
            .setMinecraftUuid(stringValue(data.get("minecraftUuid")))
            .setCurrentUsername(stringValue(data.get("currentUsername")))
            .setFirstSeen(stringValue(data.get("firstSeen")))
            .setLastSeen(stringValue(data.get("lastSeen")))
            .setCurrentServer(stringValue(data.get("currentServer")))
            .setIpAddress(stringValue(data.get("ipAddress")))
            .setCountry(stringValue(data.get("country")))
            .setProfileUrl(stringValue(data.get("profileUrl")))
            .setPunishmentsUrl(stringValue(data.get("punishmentsUrl")))
            .setTicketsUrl(stringValue(data.get("ticketsUrl")))
            .setPunishmentStats(toPlayerLookupPunishmentStats(map(data.get("punishmentStats"))))
            .setIsOnline(booleanValue(data.get("isOnline")));

        list(data.get("previousUsernames")).stream()
            .map(Objects::toString)
            .forEach(builder::addPreviousUsernames);

        listOfMaps(data.get("recentPunishments")).stream()
            .map(MinecraftPlayerProtoMapper::toPlayerLookupRecentPunishment)
            .forEach(builder::addRecentPunishments);

        listOfMaps(data.get("recentTickets")).stream()
            .map(MinecraftPlayerProtoMapper::toPlayerLookupRecentTicket)
            .forEach(builder::addRecentTickets);

        return builder.build();
    }

    private static PlayerLookupResponse.PlayerLookupPunishmentStats toPlayerLookupPunishmentStats(Map<String, Object> stats) {
        return PlayerLookupResponse.PlayerLookupPunishmentStats.newBuilder()
            .setStatus(stringValue(stats.get("status")))
            .setTotalPunishments(intValue(stats.get("totalPunishments")))
            .setActivePunishments(intValue(stats.get("activePunishments")))
            .setBans(intValue(stats.get("bans")))
            .setMutes(intValue(stats.get("mutes")))
            .setKicks(intValue(stats.get("kicks")))
            .setWarnings(intValue(stats.get("warnings")))
            .setPoints(intValue(stats.get("points")))
            .build();
    }

    private static PlayerLookupResponse.PlayerLookupRecentPunishment toPlayerLookupRecentPunishment(Map<String, Object> punishment) {
        return PlayerLookupResponse.PlayerLookupRecentPunishment.newBuilder()
            .setId(stringValue(punishment.get("id")))
            .setType(stringValue(punishment.get("type")))
            .setIssuer(stringValue(punishment.get("issuer")))
            .setIssuedAt(stringValue(punishment.get("issuedAt")))
            .setExpiresAt(stringValue(punishment.get("expiresAt")))
            .setIsActive(booleanValue(punishment.get("isActive")))
            .build();
    }

    private static PlayerLookupResponse.PlayerLookupRecentTicket toPlayerLookupRecentTicket(Map<String, Object> ticket) {
        return PlayerLookupResponse.PlayerLookupRecentTicket.newBuilder()
            .setId(stringValue(ticket.get("id")))
            .setTitle(stringValue(ticket.get("title")))
            .setCategory(stringValue(ticket.get("category")))
            .setStatus(stringValue(ticket.get("status")))
            .setCreatedAt(stringValue(ticket.get("createdAt")))
            .setLastUpdated(stringValue(ticket.get("lastUpdated")))
            .build();
    }

    static Struct toStruct(Map<String, Object> map) {
        Struct.Builder builder = Struct.newBuilder();
        map.forEach((key, value) -> builder.putFields(key, objectToValue(value)));
        return builder.build();
    }

    private static Object valueToObject(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE, KIND_NOT_SET -> null;
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> structToMap(value.getStructValue());
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                .map(MinecraftPlayerProtoMapper::valueToObject)
                .toList();
        };
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

    private static List<?> list(Object object) {
        if (object instanceof List<?> values) {
            return values;
        }
        return List.of();
    }

    private static List<Map<String, Object>> listOfMaps(Object object) {
        return list(object).stream()
            .filter(Map.class::isInstance)
            .map(value -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) value;
                return map;
            })
            .toList();
    }

    private static Map<String, Object> map(Object object) {
        if (object instanceof Map<?, ?> rawMap) {
            return stringObjectMap(rawMap);
        }
        return Map.of();
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> map = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> map.put(Objects.toString(key), value));
        return map;
    }

    private static Object nestedOrTopLevel(Map<String, Object> nested, Map<String, Object> topLevel, String key) {
        return nested.containsKey(key) ? nested.get(key) : topLevel.get(key);
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

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string);
        }
        return false;
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

    private static void setOptionalBoolean(Consumer<Boolean> setter, Object value) {
        if (value != null) {
            setter.accept(booleanValue(value));
        }
    }

    private static void setStringIfPresent(Object target, String methodName, Object value) {
        if (value == null) {
            return;
        }
        try {
            target.getClass().getMethod(methodName, String.class).invoke(target, Objects.toString(value));
        } catch (NoSuchMethodException e) {
            log.warn("setter {} not found on {}; proto/data drift?", methodName, target.getClass().getName());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set protobuf field with " + methodName, e);
        }
    }

    private static void setBooleanIfPresent(Object target, String methodName, Object value) {
        try {
            target.getClass().getMethod(methodName, boolean.class).invoke(target, booleanValue(value));
        } catch (NoSuchMethodException e) {
            log.warn("setter {} not found on {}; proto/data drift?", methodName, target.getClass().getName());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set protobuf field with " + methodName, e);
        }
    }
}
