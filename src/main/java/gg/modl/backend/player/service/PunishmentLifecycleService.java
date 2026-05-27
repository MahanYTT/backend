package gg.modl.backend.player.service;

import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.PunishmentMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.util.MongoKeyUtils;
import gg.modl.backend.player.controller.v1.MinecraftPunishmentController.MinecraftCreatePunishmentRequest;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.EnforcementCategory;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.dto.request.CreateEvidenceRequest;
import gg.modl.backend.player.dto.request.CreateNoteRequest;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentContext;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.settings.service.WebhookSettingsService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.ticket.service.TicketService;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PunishmentLifecycleService {
    private final PlayerMongoRepository playerRepository;
    private final PunishmentMongoRepository punishmentRepository;
    private final TicketService ticketService;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentTypeService punishmentTypeService;
    private final OffenderThresholdSettingsService thresholdSettingsService;
    private final PunishmentDurationCalculator durationCalculator;
    private final IssuerNameResolver issuerNameResolver;
    private final StaffMongoRepository staffRepository;
    private final PunishmentQueryService punishmentQueryService;
    private final PermissionService permissionService;
    private final WebhookSettingsService webhookSettingsService;

    public void validatePunishmentPermission(Server server, String email, int typeOrdinal) {
        if (email == null) {
            throw new ForbiddenException("No authenticated user found for permission check");
        }
        if (permissionService.isSuperAdmin(server, email)) {
            return;
        }

        PunishmentType type = punishmentTypeService.getPunishmentTypeByOrdinal(server, typeOrdinal)
            .orElseThrow(() -> new ValidationException("Invalid punishment type"));

        String applyPermission = "punishment.apply." + type.getName().toLowerCase(Locale.ROOT).replace(" ", "-");
        String role = staffRepository.findByEmailIgnoreCase(server, email)
            .map(Staff::getRole)
            .orElse(null);

        if (!permissionService.hasPermission(server, role, applyPermission)) {
            throw new ForbiddenException("You do not have permission to apply this punishment type");
        }
    }

    public String createMinecraftPunishment(Server server, MinecraftCreatePunishmentRequest request) {
        UUID playerUuid = UUID.fromString(request.targetUuid());

        List<CreateNoteRequest> noteRequests = null;
        if (request.notes() != null) {
            noteRequests = request.notes()
                .stream()
                .map(text -> new CreateNoteRequest(text, request.issuerName(), request.issuerId(), null))
                .toList();
        }

        Map<String, Object> data = request.data() != null ? MongoKeyUtils.sanitizeKeys(new HashMap<>(request.data())) : new HashMap<>();
        data.put("pendingAcknowledgement", true);

        CreatePunishmentRequest serviceRequest = new CreatePunishmentRequest(
            request.issuerName(),
            request.issuerId(),
            request.typeOrdinal(),
            noteRequests,
            null,
            request.attachedTicketIds(),
            request.severity(),
            request.status(),
            data,
            request.reason(),
            request.duration()
        );

        return createPunishment(server, playerUuid, serviceRequest);
    }

    public String createPunishment(Server server, UUID playerUuid, CreatePunishmentRequest request) {
        Player player = playerRepository.findByMinecraftUuid(server, playerUuid.toString()).orElse(null);

        if (player == null) {
            throw new ResourceNotFoundException("Player not found");
        }
        Date now = new Date();
        Map<String, Object> data = request.data() != null ? MongoKeyUtils.sanitizeKeys(new HashMap<>(request.data())) : new HashMap<>();

        if (request.severity() != null) {
            data.put("severity", request.severity());
        }
        if (request.status() != null) {
            data.put("status", request.status());
        }

        Long calculatedDuration = request.duration();
        if (calculatedDuration == null && request.severity() != null) {
            PunishmentDurationCalculator.DurationResult result =
                durationCalculator.calculate(server, player.getPunishments(), request.typeOrdinal(), request.severity());
            calculatedDuration = result.duration();

            if (result.status() != null && (!data.containsKey("status") || PunishmentData.getStatus(data) == null)) {
                data.put("status", result.status());
            }
        }

        if (calculatedDuration == null) {
            calculatedDuration = PunishmentData.getDuration(data);
        }

        if (calculatedDuration != null && calculatedDuration != 0) {
            data.put("duration", calculatedDuration);
        }
        if (request.reason() != null && !request.reason().isBlank()) {
            data.put("reason", request.reason());
        }

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        Map<Integer, PunishmentType> typesByOrdinal = PunishmentTypeIndex.byOrdinal(types);
        PunishmentType newPunishmentType = typesByOrdinal.get(request.typeOrdinal());

        if (newPunishmentType != null) {
            if (newPunishmentType.isPermanentUntilUsernameChange() && !data.containsKey("blockedName")) {
                String currentUsername = PlayerDataUtils.extractLatestUsername(player.getUsernames());
                currentUsername = "Unknown".equals(currentUsername) ? null : currentUsername;
                if (currentUsername != null) {
                    data.put("blockedName", currentUsername);
                }
            }
            if (newPunishmentType.isPermanentUntilSkinChange() && !data.containsKey("blockedSkin")) {
                Object skinHash = player.getData() != null ? player.getData().get("lastSkinHash") : null;
                if (skinHash instanceof String s) {
                    data.put("blockedSkin", s);
                }
            }
        }

        String newCategory = statusCalculator.getEffectiveCategory(newPunishmentType, data);
        if (newCategory != null) {
            boolean hasExistingInCategory = player.getPunishments()
                .stream().anyMatch(existing -> {
                    String existingCategory = statusCalculator.getEffectiveCategory(existing, types);
                    if (!newCategory.equals(existingCategory)) {
                        return false;
                    }

                    boolean active = statusCalculator.isPunishmentActive(existing);
                    boolean unstarted = isUnstarted(existing);
                    return active || unstarted;
                });

            if (hasExistingInCategory) {
                data.put("status", PunishmentStatus.UNSTARTED);
            }
        }

        String reqIssuerName = request.issuerId() != null ? null : request.issuerName();
        String reqIssuerId = request.issuerId();

        List<PunishmentNote> notes = new ArrayList<>();
        String enforcementType = newPunishmentType != null && newPunishmentType.isKick() ? "kick"
                                                                                         : EnforcementCategory.BAN.name().equals(newCategory) ? "ban"
                                                                                                                     : EnforcementCategory.MUTE.name().equals(newCategory) ? "mute"
                                                                                                                                                  : "punishment";
        String issuedNote = calculatedDuration != null && calculatedDuration > 0
                            ? "issued " + PunishmentMapper.formatDuration(calculatedDuration, false) + " " + enforcementType
                            : "issued permanent " + enforcementType;
        if ("kick".equals(enforcementType)) {
            issuedNote = "issued kick";
        }
        notes.add(new PunishmentNote(
            IdGenerator.generateShortId(),
            issuedNote,
            now,
            reqIssuerName,
            reqIssuerId
        ));
        if (request.reason() != null && !request.reason().isBlank()) {
            notes.add(new PunishmentNote(
                IdGenerator.generateShortId(),
                request.reason(),
                now,
                reqIssuerName,
                reqIssuerId
            ));
        }
        if (request.notes() != null) {
            for (CreateNoteRequest noteRequest : request.notes()) {
                String noteIssuerId = noteRequest.issuerId() != null ? noteRequest.issuerId() : reqIssuerId;
                String noteIssuerName = noteIssuerId != null ? null : (noteRequest.issuerName() != null ? noteRequest.issuerName() : request.issuerName());
                notes.add(new PunishmentNote(IdGenerator.generateShortId(), noteRequest.text(), now, noteIssuerName, noteIssuerId));
            }
        }

        List<PunishmentEvidence> evidence = new ArrayList<>();
        if (request.evidence() != null) {
            for (CreateEvidenceRequest evidenceRequest : request.evidence()) {
                String evIssuerId = evidenceRequest.issuerId() != null ? evidenceRequest.issuerId() : reqIssuerId;
                String evIssuerName = evIssuerId != null ? null : (evidenceRequest.issuerName() != null ? evidenceRequest.issuerName() : request.issuerName());
                String type = evidenceRequest.type() != null ? evidenceRequest.type() : "text";
                evidence.add(new PunishmentEvidence(
                    evidenceRequest.text(),
                    evidenceRequest.fileUrl(),
                    type,
                    evIssuerName,
                    evIssuerId,
                    now,
                    evidenceRequest.fileName(),
                    evidenceRequest.fileType(),
                    evidenceRequest.fileSize()
                ));
            }
        }

        String punishmentId = IdGenerator.generateShortId();

        Boolean.TRUE.equals(data.remove("pendingAcknowledgement"));
        Date startedDate = null;

        Punishment punishment = new Punishment(
            punishmentId,
            request.typeOrdinal(),
            reqIssuerName,
            reqIssuerId,
            now,
            startedDate,
            new ArrayList<>(),
            notes,
            evidence,
            request.attachedTicketIds() != null ? request.attachedTicketIds() : new ArrayList<>(),
            data
        );

        player.getPunishments().add(punishment);
        persistPlayerPunishments(server, player);

        String playerName = PlayerDataUtils.extractLatestUsername(player.getUsernames());
        String typeName = newPunishmentType != null ? newPunishmentType.getName() : "Unknown";
        String severity = request.severity() != null ? request.severity() : "";
        String reason = request.reason() != null ? request.reason() : "";
        String duration = calculatedDuration != null
            ? PunishmentMapper.formatDuration(calculatedDuration, calculatedDuration < 0)
            : "Permanent";
        String issuerDisplay = issuerNameResolver.resolve(request.issuerId(), request.issuerName(), server);
        String ticketIds = request.attachedTicketIds() != null ? String.join(", ", request.attachedTicketIds()) : "";

        webhookSettingsService.sendPunishmentCreatedWebhook(server, Map.of(
            "id", punishmentId,
            "playerName", playerName,
            "type", typeName,
            "severity", severity,
            "reason", reason,
            "duration", duration,
            "issuer", issuerDisplay,
            "ticketId", ticketIds
        ));

        if (request.attachedTicketIds() != null && !request.attachedTicketIds().isEmpty()) {
            String ticketIssuerName = issuerNameResolver.resolve(request.issuerId(), request.issuerName(), server);
            closeAttachedTickets(server, request.attachedTicketIds(), ticketIssuerName);
        }

        return punishmentId;
    }

    private void closeAttachedTickets(Server server, List<String> ticketIds, String issuerName) {
        for (String ticketId : ticketIds) {
            try {
                ticketService.closeTicketForPunishment(server, ticketId, issuerName);
            } catch (Exception e) {
                log.error("[TICKET_CLOSE] Failed to close ticket {}", ticketId, e);
            }
        }
    }

    private boolean isUnstarted(Punishment punishment) {
        Map<String, Object> data = punishment.getData();
        if (data == null) {
            return false;
        }

        String status = PunishmentData.getStatus(data);
        if (!PunishmentStatus.UNSTARTED.equals(status)) {
            return false;
        }

        for (PunishmentModification mod : punishment.getModifications()) {
            if (PunishmentModificationType.isPardon(mod.type())) {
                return false;
            }
        }
        return true;
    }

    private void persistPlayerPunishments(Server server, Player player) {
        punishmentRepository.replacePunishments(server, player);
    }

    public PunishmentOperationResult acknowledgePunishment(Server server, UUID playerUuid, String punishmentId) {
        Player player = playerRepository.findByMinecraftUuid(server, playerUuid.toString()).orElse(null);
        if (player == null) {
            return new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Player not found: " + playerUuid, false, 0);
        }

        Punishment punishment = findPunishment(player, punishmentId);
        if (punishment == null) {
            return new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND,
                "Punishment not found: " + punishmentId + " for player: " + playerUuid, false, 0);
        }

        if (punishment.getStarted() != null) {
            return new PunishmentOperationResult(PunishmentOperationStatus.NO_OP, "Punishment already acknowledged", true, 0);
        }

        punishment.setStarted(new Date());
        if (punishment.getData() != null) {
            punishment.getData().remove("status");
        }
        persistPlayerPunishments(server, player);

        return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment acknowledged", true, 1);
    }

    private Punishment findPunishment(Player player, String punishmentId) {
        return PunishmentQueryService.findPunishment(player, punishmentId);
    }

    public PunishmentOperationResult pardonPunishment(Server server, String punishmentId, String issuerName, String issuerId, String reason) {
        PunishmentContext context = punishmentQueryService.findPunishmentContext(server, punishmentId).orElse(null);
        if (context == null) {
            return new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0);
        }

        Punishment punishment = context.punishment();
        if (isPardoned(punishment)) {
            return new PunishmentOperationResult(PunishmentOperationStatus.NO_OP,
                "Punishment has already been pardoned", false, 0);
        }

        String resolvedIssuerName = issuerId != null ? null : issuerName;

        Date now = new Date();

        punishment.getModifications().add(new PunishmentModification(
            IdGenerator.generateShortId(),
            PunishmentModificationType.MANUAL_PARDON.name(),
            now,
            resolvedIssuerName,
            issuerId,
            reason != null ? reason : "",
            null,
            null,
            null
        ));

        punishment.getNotes().add(new PunishmentNote(
            IdGenerator.generateShortId(),
            "pardoned punishment",
            now,
            resolvedIssuerName,
            issuerId
        ));
        if (reason != null && !reason.isBlank()) {
            punishment.getNotes().add(new PunishmentNote(
                IdGenerator.generateShortId(),
                reason,
                now,
                resolvedIssuerName,
                issuerId
            ));
        }

        punishment.getData().put("status", PunishmentStatus.PARDONED);
        persistPlayerPunishments(server, context.player());

        if (PunishmentData.isAltBlocking(punishment.getData())) {
            cascadePardonLinkedBans(server, punishmentId);
        }

        return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment pardoned", true, 1);
    }

    public int cascadePardonLinkedBans(Server server, String parentPunishmentId) {
        return cascadePardonLinkedBansInternal(
            punishmentRepository.findByLinkedBanId(server, parentPunishmentId),
            parentPunishmentId,
            player -> persistPlayerPunishments(server, player)
        );
    }

    private int cascadePardonLinkedBansInternal(
        List<Player> players,
        String parentPunishmentId,
        LinkedBanSaveAction saveAction
    ) {
        int count = 0;

        for (Player player : players) {
            int pardonedPunishments = applyLinkedBanSystemPardon(player, parentPunishmentId);
            if (pardonedPunishments <= 0) {
                continue;
            }

            saveAction.save(player);
            count += pardonedPunishments;
        }

        return count;
    }

    private int applyLinkedBanSystemPardon(Player player, String parentPunishmentId) {
        int count = 0;

        for (Punishment punishment : player.getPunishments()) {
            if (punishment.getTypeOrdinal() != Punishment.LINKED_BAN_TYPE_ORDINAL
                || punishment.getData() == null
                || !parentPunishmentId.equals(punishment.getData().get("linkedBanId"))
                || !statusCalculator.isPunishmentActive(punishment)) {
                continue;
            }

            addSystemPardon(punishment, "Auto-pardoned: parent ban was pardoned", new Date());
            count++;
        }

        return count;
    }

    private void addSystemPardon(Punishment punishment, String reason, Date now) {
        punishment.getModifications().add(new PunishmentModification(
            IdGenerator.generateShortId(),
            PunishmentModificationType.SYSTEM_PARDON.name(),
            now,
            "System",
            null,
            reason,
            null,
            null,
            null
        ));
        punishment.getNotes().add(new PunishmentNote(
            IdGenerator.generateShortId(),
            reason,
            now,
            "System",
            null
        ));
    }

    private boolean isPardoned(Punishment punishment) {
        return punishment.getModifications()
            .stream()
            .anyMatch(modification ->
                PunishmentModificationType.isPardon(modification.type()));
    }

    public int cascadePardonLinkedBans(String databaseName, String parentPunishmentId) {
        return cascadePardonLinkedBansInternal(
            punishmentRepository.findByLinkedBanId(databaseName, parentPunishmentId),
            parentPunishmentId,
            player -> persistPlayerPunishments(databaseName, player)
        );
    }

    private void persistPlayerPunishments(String databaseName, Player player) {
        punishmentRepository.replacePunishments(databaseName, player);
    }

    public int cascadeDurationChangeToLinkedBans(Server server, String parentPunishmentId, Long newDuration, String issuerName) {
        int count = 0;

        for (Player player : punishmentRepository.findByLinkedBanId(server, parentPunishmentId)) {
            int updatedPunishments = applyLinkedBanDurationChange(player, parentPunishmentId, newDuration);
            if (updatedPunishments <= 0) {
                continue;
            }

            persistPlayerPunishments(server, player);
            count += updatedPunishments;
        }

        return count;
    }

    private int applyLinkedBanDurationChange(Player player, String parentPunishmentId, Long newDuration) {
        int count = 0;

        for (Punishment punishment : player.getPunishments()) {
            if (punishment.getTypeOrdinal() != Punishment.LINKED_BAN_TYPE_ORDINAL
                || punishment.getData() == null
                || !parentPunishmentId.equals(punishment.getData().get("linkedBanId"))
                || !statusCalculator.isPunishmentActive(punishment)) {
                continue;
            }

            Date now = new Date();
                punishment.getData().put("duration", newDuration);
            punishment.getModifications().add(new PunishmentModification(
                IdGenerator.generateShortId(),
                PunishmentModificationType.MANUAL_DURATION_CHANGE.name(),
                now,
                "System",
                null,
                "Cascaded from parent ban duration change",
                newDuration,
                null,
                null
            ));
            punishment.getNotes().add(new PunishmentNote(
                IdGenerator.generateShortId(),
                "Duration changed (cascaded from parent ban)",
                now,
                "System",
                null
            ));
            if (punishment.getStarted() == null) {
                punishment.setStarted(now);
            }
            count++;
        }

        return count;
    }

    public List<String> promoteUnstartedPunishments(Server server, Player player) {
        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        List<String> promotedIds = new ArrayList<>();

        for (String category : List.of(EnforcementCategory.BAN.name(), EnforcementCategory.MUTE.name())) {
            boolean hasActive = player.getPunishments()
                .stream().anyMatch(p -> {
                    String effectiveCategory = statusCalculator.getEffectiveCategory(p, types);
                    return category.equals(effectiveCategory) && statusCalculator.isPunishmentActive(p);
                });

            if (hasActive) {
                continue;
            }

            Optional<Punishment> oldest = player.getPunishments()
                .stream()
                .filter(p -> {
                    String effectiveCategory = statusCalculator.getEffectiveCategory(p, types);
                    return category.equals(effectiveCategory) && isUnstarted(p);
                })
                .min((a, b) -> a.getIssued().compareTo(b.getIssued()));

            if (oldest.isPresent()) {
                Punishment toPromote = oldest.get();
                punishmentRepository.unsetPunishmentStatus(server, player.getMinecraftUuid().toString(), toPromote.getId());
                promotedIds.add(toPromote.getId());
            }
        }

        return promotedIds;
    }

    public List<String> enforceAltBlockingBans(Server server, Player player) {
        List<String> createdIds = new ArrayList<>();

        List<String> linkedUuids = getLinkedAccountUuids(player);
        if (linkedUuids.isEmpty()) {
            return createdIds;
        }

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);

        boolean alreadyBanned = player.getPunishments()
            .stream().anyMatch(p -> {
                String cat = statusCalculator.getEffectiveCategory(p, types);
                return EnforcementCategory.BAN.name().equals(cat) && statusCalculator.isPunishmentActive(p);
            });
        if (alreadyBanned) {
            return createdIds;
        }

        List<Player> linkedPlayers = playerRepository.findByMinecraftUuids(server, linkedUuids);

        for (Player linkedPlayer : linkedPlayers) {
            for (Punishment punishment : linkedPlayer.getPunishments()) {
                if (!statusCalculator.isPunishmentActive(punishment)) {
                    continue;
                }

                Map<String, Object> data = punishment.getData();
                if (data == null) {
                    continue;
                }

                if (!PunishmentData.isAltBlocking(data)) {
                    continue;
                }

                String cat = statusCalculator.getEffectiveCategory(punishment, types);
                if (!EnforcementCategory.BAN.name().equals(cat)) {
                    continue;
                }

                Date parentExpiry = statusCalculator.getEffectiveExpiry(punishment);
                Long linkedDuration = null;
                if (parentExpiry != null) {
                    long remaining = parentExpiry.getTime() - System.currentTimeMillis();
                    if (remaining <= 0) {
                        continue;
                    }
                    linkedDuration = remaining;
                }

                String linkedBanId = createLinkedBanPunishment(
                    server, player.getMinecraftUuid(),
                    punishment.getId(),
                    linkedPlayer.getMinecraftUuid().toString(),
                    linkedDuration
                );
                createdIds.add(linkedBanId);

                return createdIds;
            }
        }

        return createdIds;
    }

    private String createLinkedBanPunishment(Server server, UUID playerUuid, String parentPunishmentId, String parentPlayerUuid, Long duration) {
        Player player = playerRepository.findByMinecraftUuid(server, playerUuid.toString()).orElse(null);
        if (player == null) {
            throw new ResourceNotFoundException("Player not found");
        }

        Date now = new Date();
        Map<String, Object> data = new HashMap<>();
        data.put("linkedBanId", parentPunishmentId);
        data.put("linkedBanParentUuid", parentPlayerUuid);
        if (duration != null) {
            data.put("duration", duration);
        }

        String punishmentId = IdGenerator.generateShortId();

        List<PunishmentNote> notes = new ArrayList<>();
        String linkedBanNote = duration != null && duration > 0
                               ? "issued " + PunishmentMapper.formatDuration(duration, false) + " ban"
                               : "issued permanent ban";
        notes.add(new PunishmentNote(
            IdGenerator.generateShortId(),
            linkedBanNote,
            now,
            "System",
            null
        ));
        notes.add(new PunishmentNote(
            IdGenerator.generateShortId(),
            "Automatically issued linked ban due to alt-blocking ban on linked account",
            now,
            "System",
            null
        ));

        Punishment punishment = new Punishment(
            punishmentId,
            Punishment.LINKED_BAN_TYPE_ORDINAL,
            "System",
            null,
            now,
            now,
            new ArrayList<>(),
            notes,
            new ArrayList<>(),
            new ArrayList<>(),
            data
        );

        player.getPunishments().add(punishment);
        persistPlayerPunishments(server, player);

        return punishmentId;
    }

    @SuppressWarnings("unchecked")
    private List<String> getLinkedAccountUuids(Player player) {
        if (player.getData() == null) {
            return List.of();
        }
        Object linkedObj = player.getData().get("linkedAccounts");
        if (linkedObj instanceof List<?> list) {
            return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        }
        return List.of();
    }

    public List<String> checkRestrictionAutoPardons(Server server, Player player, String currentUsername, String currentSkinHash) {
        List<String> pardonedIds = new ArrayList<>();
        Map<Integer, PunishmentType> typesByOrdinal = PunishmentTypeIndex.byOrdinal(punishmentTypeService.getPunishmentTypes(server));

        for (Punishment punishment : player.getPunishments()) {
            if (!statusCalculator.isPunishmentActive(punishment)) {
                continue;
            }

            PunishmentType type = typesByOrdinal.get(punishment.getTypeOrdinal());
            if (type == null) {
                continue;
            }

            Map<String, Object> data = punishment.getData();
            if (data == null) {
                continue;
            }

            if (type.isPermanentUntilUsernameChange() && currentUsername != null) {
                String blockedName = PunishmentData.getBlockedName(data);
                if (blockedName != null && !blockedName.equalsIgnoreCase(currentUsername)) {
                    String reason = "Auto-pardoned: username changed from '" + blockedName + "' to '" + currentUsername + "'";
                    systemPardonPunishment(server, player.getMinecraftUuid(), punishment.getId(), reason);
                    pardonedIds.add(punishment.getId());
                }
            }

            if (type.isPermanentUntilSkinChange() && currentSkinHash != null) {
                String blockedSkin = PunishmentData.getBlockedSkin(data);
                if (blockedSkin != null && !blockedSkin.equals(currentSkinHash)) {
                    String reason = "Auto-pardoned: skin changed";
                    systemPardonPunishment(server, player.getMinecraftUuid(), punishment.getId(), reason);
                    pardonedIds.add(punishment.getId());
                }
            }
        }

        return pardonedIds;
    }

    public void systemPardonPunishment(Server server, UUID playerUuid, String punishmentId, String reason) {
        Player player = playerRepository.findByMinecraftUuid(server, playerUuid.toString()).orElse(null);
        if (player == null) {
            return;
        }

        Punishment punishment = findPunishment(player, punishmentId);
        if (punishment == null) {
            return;
        }

        addSystemPardon(punishment, reason, new Date());
        persistPlayerPunishments(server, player);
    }

    @FunctionalInterface
    private interface LinkedBanSaveAction {
        void save(Player player);
    }
}
