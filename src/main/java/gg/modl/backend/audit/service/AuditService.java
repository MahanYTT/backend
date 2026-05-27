package gg.modl.backend.audit.service;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.audit.dto.response.ActivePunishmentResponse;
import gg.modl.backend.audit.dto.response.PunishmentAuditResponse;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.repository.AuditMongoRepository;
import gg.modl.backend.infrastructure.exception.ExternalServiceException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.service.PlayerStatusCalculator;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.backend.infrastructure.util.DateRangeUtil;
import gg.modl.backend.infrastructure.util.IdGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditMongoRepository auditRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final StaffService staffService;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentLifecycleService punishmentLifecycleService;

    public List<PunishmentAuditResponse> getPunishments(
        Server server, int limit, boolean canRollbackOnly) {
        final Date thirtyDaysAgo = DateRangeUtil.getStartDate("30d");
        final List<AuditLog> logs =
            auditRepository.findPunishmentLogs(server, thirtyDaysAgo, limit, canRollbackOnly);

        return logs.stream().map(logEntry -> {
            Map<String, Object> metadata = logEntry.getMetadata() != null
                                           ? logEntry.getMetadata() : Collections.emptyMap();
            return new PunishmentAuditResponse(
                logEntry.getId(),
                extractPunishmentType(logEntry.getDescription()),
                getStringFromMetadata(metadata, "playerId", "unknown"),
                getStringFromMetadata(metadata, "playerName", "Unknown"),
                getStringFromMetadata(metadata, "staffId", logEntry.getSource()),
                logEntry.getSource(),
                getStringFromMetadata(metadata, "reason",
                    logEntry.getDescription()),
                getStringFromMetadata(metadata, "duration", null),
                logEntry.getCreated(),
                !Boolean.FALSE.equals(metadata.get("canRollback"))
            );
        }).toList();
    }

    private String extractPunishmentType(String description) {
        if (description == null) {
            return "Unknown";
        }
        String lower = description.toLowerCase();
        if (lower.contains("ban")) {
            return "Ban";
        }
        if (lower.contains("mute")) {
            return "Mute";
        }
        if (lower.contains("kick")) {
            return "Kick";
        }
        if (lower.contains("warn")) {
            return "Warn";
        }
        return "Unknown";
    }

    private String getStringFromMetadata(
        Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof String stringValue) {
            return stringValue;
        }
        return defaultValue;
    }

    public List<ActivePunishmentResponse> getActivePunishments(Server server) {
        return getPunishmentsList(server, "active");
    }

    public List<ActivePunishmentResponse> getPunishmentsList(Server server, String statusFilter) {
        final List<PunishmentType> punishmentTypes = punishmentTypeService.getPunishmentTypes(server);
        final Map<Integer, PunishmentType> typesByOrdinal = PunishmentTypeIndex.byOrdinal(punishmentTypes);
        final List<Document> rows = auditRepository.aggregatePunishmentRows(server);
        final Map<String, String> resolvedIssuers = resolveIssuerNames(server, rows);

        final boolean filterActive = "active".equalsIgnoreCase(statusFilter);
        final boolean filterInactive = "inactive".equalsIgnoreCase(statusFilter);

        List<ActivePunishmentResponse> results = new ArrayList<>();
        for (Document row : rows) {
            Punishment punishment = reconstructPunishment(row);
            boolean active = statusCalculator.isPunishmentActive(punishment);

            if (filterActive && !active) {
                continue;
            }
            if (filterInactive && active) {
                continue;
            }

            results.add(mapToActivePunishmentResponse(
                server, row, punishment, active, typesByOrdinal, resolvedIssuers));
        }

        return results;
    }

    private ActivePunishmentResponse mapToActivePunishmentResponse(
        Server server, Document row, Punishment punishment, boolean active,
        Map<Integer, PunishmentType> typesByOrdinal, Map<String, String> resolvedIssuers) {
        final int typeOrdinal = row.getInteger("typeOrdinal", 0);
        final String typeName = punishmentTypeService.getPunishmentTypeName(server, typeOrdinal);
        final PunishmentType matchedType = typesByOrdinal.get(typeOrdinal);
        final String category = matchedType != null
            ? (matchedType.getCategory() != null ? matchedType.getCategory() : "Administrative")
            : "Administrative";

        final Document data = row.get("data", Document.class);
        final String reason = data != null ? data.getString("reason") : null;
        final Long duration = extractDuration(data);
        final List<ActivePunishmentResponse.EvidenceItem> evidenceItems = extractEvidenceItems(row);

        List<String> ticketIds = row.getList("attachedTicketIds", String.class);
        if (ticketIds == null) {
            ticketIds = Collections.emptyList();
        }

        return new ActivePunishmentResponse(
            row.getString("punishmentId"),
            row.getString("playerId"),
            AuditDocumentUtil.extractPlayerNameFromDoc(row),
            typeName,
            typeOrdinal,
            category,
            resolveIssuerFromDoc(
                row.getString("issuerId"),
                row.getString("issuerName"),
                resolvedIssuers),
            reason,
            duration,
            row.getDate("issued"),
            row.getDate("started"),
            statusCalculator.getEffectiveExpiry(punishment),
            active,
            !evidenceItems.isEmpty(),
            evidenceItems.size(),
            evidenceItems,
            ticketIds
        );
    }

    private List<ActivePunishmentResponse.EvidenceItem> extractEvidenceItems(Document row) {
        List<Document> evidenceDocs = row.getList("evidence", Document.class);
        if (evidenceDocs == null) {
            return Collections.emptyList();
        }

        List<ActivePunishmentResponse.EvidenceItem> items = new ArrayList<>();
        for (Document evidenceDoc : evidenceDocs) {
            items.add(new ActivePunishmentResponse.EvidenceItem(
                evidenceDoc.getString("text"),
                evidenceDoc.getString("url"),
                evidenceDoc.getString("type"),
                evidenceDoc.getString("fileName")
            ));
        }
        return items;
    }

    private static String resolveIssuerFromDoc(
        String issuerId, String issuerName, Map<String, String> resolvedIssuers) {
        if (issuerId != null && resolvedIssuers.containsKey(issuerId)) {
            return resolvedIssuers.get(issuerId);
        }
        if (issuerName != null) {
            return issuerName;
        }
        return issuerId != null ? "Unknown Staff" : "Console";
    }

    private Long extractDuration(Document data) {
        if (data == null) {
            return null;
        }
        Object durationObj = data.get("duration");
        if (durationObj instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private Map<String, String> resolveIssuerNames(Server server, List<Document> rows) {
        Set<String> issuerIds = new HashSet<>();
        for (Document doc : rows) {
            String issuerId = doc.getString("issuerId");
            if (issuerId != null) {
                issuerIds.add(issuerId);
            }
        }
        return auditRepository.mapStaffUsernamesByIds(server, issuerIds);
    }

    private Punishment reconstructPunishment(Document doc) {
        Punishment punishment = new Punishment();
        punishment.setId(doc.getString("punishmentId"));
        punishment.setTypeOrdinal(doc.getInteger("typeOrdinal", 0));
        punishment.setIssuerName(
            doc.getString("issuerName") != null ? doc.getString("issuerName") : "Unknown");
        punishment.setIssuerId(doc.getString("issuerId"));
        punishment.setIssued(
            doc.getDate("issued") != null ? doc.getDate("issued") : new Date());
        punishment.setStarted(doc.getDate("started"));

        Document data = doc.get("data", Document.class);
        if (data != null) {
            punishment.setData(new HashMap<>(data));
        }

        punishment.setModifications(extractModifications(doc));
        punishment.setNotes(Collections.emptyList());
        punishment.setEvidence(Collections.emptyList());
        punishment.setAttachedTicketIds(Collections.emptyList());

        return punishment;
    }

    private List<PunishmentModification> extractModifications(Document doc) {
        List<Document> modDocs = doc.getList("modifications", Document.class);
        if (modDocs == null) {
            return new ArrayList<>();
        }

        List<PunishmentModification> mods = new ArrayList<>();
        for (Document modDoc : modDocs) {
            Long effectiveDuration = null;
            Object edObj = modDoc.get("effectiveDuration");
            if (edObj instanceof Number num) {
                effectiveDuration = num.longValue();
            }
            mods.add(new PunishmentModification(
                modDoc.getString("id"),
                modDoc.getString("type"),
                modDoc.getDate("date"),
                modDoc.getString("issuerName"),
                modDoc.getString("issuerId"),
                modDoc.getString("reason"),
                effectiveDuration,
                modDoc.getString("appealTicketId"),
                null
            ));
        }
        return mods;
    }

    public boolean rollbackPunishment(
        Server server, String punishmentId, String reason, String performerUsername) {
        final AuditLog punishment = auditRepository.findAuditLogById(server, punishmentId);
        if (punishment == null) {
            return false;
        }

        final Map<String, Object> metadata = punishment.getMetadata();
        if (metadata != null && Boolean.FALSE.equals(metadata.get("canRollback"))) {
            throw new ValidationException("This punishment cannot be rolled back");
        }

        final AuditLog rollbackLog = AuditLog.builder()
            .created(new Date())
            .level("moderation")
            .source(performerUsername)
            .description("Rolled back " + extractPunishmentType(punishment.getDescription())
                         + " for "
                         + (metadata != null ? metadata.get("playerName") : "unknown player"))
            .metadata(Map.of(
                "originalPunishmentId", punishmentId,
                "rollbackReason", reason != null ? reason : "Admin rollback",
                "originalPunishment", Map.of(
                    "type", extractPunishmentType(punishment.getDescription()),
                    "player", metadata != null
                              ? metadata.getOrDefault("playerName", "") : "",
                    "staff", punishment.getSource(),
                    "originalReason", metadata != null
                                      ? metadata.getOrDefault("reason", "") : ""
                )
            ))
            .build();

        auditRepository.saveAuditLog(server, rollbackLog);
        auditRepository.markAuditLogRolledBack(
            server, punishmentId, performerUsername, new Date());
        return true;
    }

    public Map<String, Object> getDatabaseTable(
        Server server, String table, int limit, int skip) {
        final List<String> allowedTables =
            List.of("players", "tickets", "staff", "punishments", "logs", "settings");
        if (!allowedTables.contains(table)) {
            throw new ValidationException("Invalid table name");
        }

        final String collectionName = getCollectionName(table);
        final List<Document> documents = auditRepository.readTable(server, collectionName, limit, skip);
        final long total = auditRepository.countCollection(server, collectionName);

        return Map.of(
            "data", documents,
            "total", total,
            "limit", limit,
            "skip", skip
        );
    }

    private String getCollectionName(String table) {
        return switch (table) {
            case "players" -> CollectionName.PLAYERS;
            case "tickets" -> CollectionName.TICKETS;
            case "staff" -> CollectionName.STAFF;
            case "logs", "punishments" -> CollectionName.LOGS;
            case "settings" -> CollectionName.SETTINGS;
            default -> throw new ValidationException("Unknown table: " + table);
        };
    }

    public int rollbackAllPunishmentsByStaff(
        Server server, String staffUsername, String reason, String performerUsername) {
        final String staffId = staffService.getStaffByUsername(server, staffUsername)
            .map(StaffResponse::id)
            .orElse(null);
        return rollbackPunishmentsInternal(
            server, staffUsername, staffId, null, null, reason, performerUsername);
    }

    private int rollbackPunishmentsInternal(
        Server server, String staffUsername, String staffId,
        Date startDate, Date endDate, String reason, String performerUsername) {
        try {
            final List<Document> players =
                auditRepository.findPlayersForRollback(server, staffUsername, staffId);
            final Date now = new Date();
            final Map<String, Object> rollbackModification = new HashMap<>();
            rollbackModification.put("type", PunishmentModificationType.ROLLBACK.name());
            rollbackModification.put("timestamp", now);
            rollbackModification.put("performedBy", performerUsername);
            rollbackModification.put("reason", reason);

            int rollbackCount = 0;
            for (Document player : players) {
                rollbackCount += applyRollbackToPlayer(
                    server, player, staffUsername, staffId,
                    startDate, endDate, reason, performerUsername,
                    rollbackModification, now);
            }
            return rollbackCount;
        } catch (Exception e) {
            log.error("Error during bulk rollback for staff {}", staffUsername, e);
            throw new ExternalServiceException("Failed to rollback punishments", e);
        }
    }

    private int applyRollbackToPlayer(
        Server server, Document player, String staffUsername, String staffId,
        Date startDate, Date endDate, String reason, String performerUsername,
        Map<String, Object> rollbackModification, Date now) {
        final String playerId = player.getString("_id");
        final List<Document> punishments = player.getList("punishments", Document.class);
        if (punishments == null) {
            return 0;
        }

        final String playerName = AuditDocumentUtil.extractPlayerNameFromDoc(player);
        int count = 0;

        for (Document punishment : punishments) {
            if (!matchesIssuer(punishment, staffUsername, staffId)) {
                continue;
            }
            if (!isWithinDateRange(punishment.getDate("issued"), startDate, endDate)) {
                continue;
            }
            if (AuditDocumentUtil.hasModificationType(punishment, PunishmentModificationType.ROLLBACK.name())) {
                continue;
            }

            final String punishmentId = punishment.getString("_id");
            auditRepository.appendRollbackModification(
                server, playerId, punishmentId, rollbackModification);

            final int typeOrdinal = punishment.getInteger("typeOrdinal", 0);
            final String typeName =
                punishmentTypeService.getPunishmentTypeName(server, typeOrdinal);

            AuditLog rollbackLog = AuditLog.builder()
                .created(now)
                .level("moderation")
                .source(performerUsername)
                .description("Bulk rollback: " + typeName + " for " + playerName
                             + " (issued by " + staffUsername + ")")
                .metadata(Map.of(
                    "punishmentId", punishmentId != null ? punishmentId : "",
                    "playerId", playerId != null ? playerId : "",
                    "playerName", playerName,
                    "staffUsername", staffUsername,
                    "rollbackReason", reason != null ? reason : "Bulk rollback",
                    "punishmentType", typeName,
                    "bulkRollback", true
                ))
                .build();

            auditRepository.saveAuditLog(server, rollbackLog);
            count++;
        }

        return count;
    }

    private boolean matchesIssuer(
        Document punishment, String staffUsername, String staffId) {
        String issuerName = punishment.getString("issuerName");
        String issuerId = punishment.getString("issuerId");
        return (issuerName != null && issuerName.equalsIgnoreCase(staffUsername))
               || (staffId != null && staffId.equals(issuerId));
    }

    private boolean isWithinDateRange(Date issued, Date startDate, Date endDate) {
        if (startDate == null || endDate == null) {
            return true;
        }
        return issued != null && !issued.before(startDate) && !issued.after(endDate);
    }

    public int rollbackPunishmentsByDateRange(
        Server server, String staffUsername, Date startDate, Date endDate,
        String reason, String performerUsername) {
        final String staffId = staffService.getStaffByUsername(server, staffUsername)
            .map(StaffResponse::id)
            .orElse(null);
        return rollbackPunishmentsInternal(
            server, staffUsername, staffId, startDate, endDate, reason, performerUsername);
    }

    public int bulkPardonByType(
        Server server, List<Integer> typeOrdinals, String reason, String performerUsername) {
        return processBulkPunishmentAction(server, typeOrdinals, reason, performerUsername,
            "bulk pardon", (ctx) -> {
                if (AuditDocumentUtil.hasModificationType(ctx.punishmentDoc,
                        PunishmentModificationType.MANUAL_PARDON.name(),
                        PunishmentModificationType.APPEAL_ACCEPT.name(),
                        PunishmentModificationType.SYSTEM_PARDON.name())) {
                    return false;
                }

                Map<String, Object> modification = buildModification(
                    "MANUAL_PARDON", ctx.now, performerUsername, reason, null);
                auditRepository.appendPunishmentModificationWithData(
                    server, ctx.playerId, ctx.punishmentId, modification, Map.of("status", "Pardoned"));

                AuditLog pardonLog = buildBulkAuditLog(ctx, performerUsername,
                    "Bulk pardon: " + ctx.typeName + " for " + ctx.playerName,
                    Map.of("pardonReason", reason, "bulkPardon", true));
                auditRepository.saveAuditLog(server, pardonLog);

                Map<String, Object> data = ctx.punishmentDoc.get("data", Document.class);
                if (data != null && Boolean.TRUE.equals(data.get("altBlocking"))) {
                    punishmentLifecycleService.cascadePardonLinkedBans(server, ctx.punishmentId);
                }
                return true;
            });
    }

    public int bulkSetExpirationByType(
        Server server, List<Integer> typeOrdinals, long newDurationMs,
        String reason, String performerUsername) {
        final Long effectiveDuration = newDurationMs <= 0 ? null : newDurationMs;

        return processBulkPunishmentAction(server, typeOrdinals, reason, performerUsername,
            "bulk set expiration", (ctx) -> {
                Map<String, Object> modification = buildModification(
                    "MANUAL_DURATION_CHANGE", ctx.now, performerUsername, reason, effectiveDuration);
                auditRepository.appendPunishmentModificationWithData(
                    server, ctx.playerId, ctx.punishmentId, modification,
                    effectiveDuration != null ? Map.of("duration", effectiveDuration) : Map.of());

                AuditLog durationLog = buildBulkAuditLog(ctx, performerUsername,
                    "Bulk duration change: " + ctx.typeName + " for " + ctx.playerName,
                    Map.of("reason", reason, "newDurationMs", newDurationMs, "bulkDurationChange", true));
                auditRepository.saveAuditLog(server, durationLog);
                return true;
            });
    }

    private int processBulkPunishmentAction(
        Server server, List<Integer> typeOrdinals, String reason,
        String performerUsername, String operationName, BulkPunishmentAction action) {
        try {
            final List<Document> players = auditRepository.findPlayersForBulkAction(server, typeOrdinals);
            final Date now = new Date();
            int count = 0;

            final Map<Integer, String> typeNameCache = new HashMap<>();
            for (int ordinal : typeOrdinals) {
                typeNameCache.put(ordinal, punishmentTypeService.getPunishmentTypeName(server, ordinal));
            }

            for (Document player : players) {
                final String playerId = player.getString("_id");
                final List<Document> punishments = player.getList("punishments", Document.class);
                if (punishments == null) {
                    continue;
                }

                final String playerName = AuditDocumentUtil.extractPlayerNameFromDoc(player);

                for (Document punishmentDoc : punishments) {
                    final int typeOrdinal = punishmentDoc.getInteger("typeOrdinal", 0);
                    if (!typeOrdinals.contains(typeOrdinal)) {
                        continue;
                    }

                    final Punishment punishment = reconstructPunishment(punishmentDoc);
                    if (!statusCalculator.isPunishmentActive(punishment)) {
                        continue;
                    }

                    final String punishmentId = punishmentDoc.getString("_id");
                    final String typeName = typeNameCache.getOrDefault(typeOrdinal, "Unknown");

                    BulkActionContext ctx = new BulkActionContext(
                        playerId, playerName, punishmentId, punishmentDoc, typeName, typeOrdinal, now);
                    if (action.apply(ctx)) {
                        count++;
                    }
                }
            }
            return count;
        } catch (Exception e) {
            log.error("Error during {}", operationName, e);
            throw new ExternalServiceException("Failed to " + operationName, e);
        }
    }

    private Map<String, Object> buildModification(
        String type, Date now, String issuerName, String reason, Long effectiveDuration) {
        Map<String, Object> modification = new HashMap<>();
        modification.put("id", IdGenerator.generateShortId());
        modification.put("type", type);
        modification.put("date", now);
        modification.put("issuerName", issuerName);
        modification.put("reason", reason);
        if (effectiveDuration != null) {
            modification.put("effectiveDuration", effectiveDuration);
        }
        return modification;
    }

    private AuditLog buildBulkAuditLog(
        BulkActionContext ctx, String performerUsername,
        String description, Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("punishmentId", ctx.punishmentId != null ? ctx.punishmentId : "");
        metadata.put("playerId", ctx.playerId != null ? ctx.playerId : "");
        metadata.put("playerName", ctx.playerName);
        metadata.put("punishmentType", ctx.typeName);
        metadata.putAll(extraMetadata);

        return AuditLog.builder()
            .created(ctx.now)
            .level("moderation")
            .source(performerUsername)
            .description(description)
            .metadata(metadata)
            .build();
    }

    private record BulkActionContext(
        String playerId, String playerName, String punishmentId,
        Document punishmentDoc, String typeName, int typeOrdinal, Date now) {}

    @FunctionalInterface
    private interface BulkPunishmentAction {
        boolean apply(BulkActionContext ctx);
    }
}
