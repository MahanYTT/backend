package gg.modl.backend.player.service;

import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.PunishmentMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.util.UuidUtil;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.dto.response.PunishmentPreviewResponse;
import gg.modl.backend.player.dto.response.PunishmentPreviewView;
import gg.modl.backend.player.dto.response.PunishmentResponse;
import gg.modl.backend.player.dto.response.PunishmentSearchResult;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.storage.service.EvidenceUploadTokenService;
import gg.modl.backend.ticket.data.Ticket;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentQueryService {
    private final PlayerMongoRepository playerRepository;
    private final PunishmentMongoRepository punishmentRepository;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentTypeService punishmentTypeService;
    private final OffenderThresholdSettingsService thresholdSettingsService;
    private final EvidenceUploadTokenService evidenceUploadTokenService;
    private final IssuerNameResolver issuerNameResolver;
    private final StaffMongoRepository staffRepository;
    private final TicketMongoRepository ticketRepository;

    public List<PunishmentResponse> getActivePunishments(Server server, UUID playerUuid) {
        Player player = playerRepository.findByMinecraftUuid(server, playerUuid.toString()).orElse(null);
        if (player == null) {
            return new ArrayList<>();
        }

        return player.getPunishments()
            .stream()
            .filter(statusCalculator::isPunishmentActive)
            .map(p -> toPunishmentResponse(server, p))
            .toList();
    }

    private PunishmentResponse toPunishmentResponse(Server server, Punishment punishment) {
        return toPunishmentResponseWithPlayer(server, punishment, null);
    }

    private PunishmentResponse toPunishmentResponseWithPlayer(Server server, Punishment punishment, Player player) {
        Map<String, String> resolvedIssuers = resolveIssuersForPunishment(server, punishment);
        Map<String, Object> data = punishment.getData();
        boolean active = statusCalculator.isPunishmentActive(punishment);
        Date expires = statusCalculator.getEffectiveExpiry(punishment);

        String playerUuid = player != null ? player.getMinecraftUuid().toString() : null;
        String playerUsername = player != null ? PlayerDataUtils.extractLatestUsername(player.getUsernames()) : null;

        int ordinal = punishment.getTypeOrdinal();

        PunishmentType punishmentType = punishmentTypeService.getPunishmentTypeByOrdinal(server, ordinal).orElse(null);
        String effectiveCategory = statusCalculator.getEffectiveCategory(punishmentType, data);

        return new PunishmentResponse(
            punishment.getId(),
            punishmentTypeService.getPunishmentTypeName(server, ordinal),
            ordinal,
            PunishmentMapper.resolveIssuer(punishment.getIssuerId(), punishment.getIssuerName(), resolvedIssuers),
            punishment.getIssued(),
            punishment.getStarted(),
            punishmentTypeService.isAppealable(server, ordinal),
            PunishmentData.getReason(data),
            PunishmentData.getSeverity(data),
            data != null ? resolveOffenderStatus(data) : null,
            active,
            expires,
            playerUuid,
            playerUsername,
            data != null ? PunishmentData.isAltBlocking(data) : null,
            data != null ? PunishmentData.isWipeAfterExpiry(data) : null,
            effectiveCategory,
            resolveModifications(punishment.getModifications(), resolvedIssuers),
            resolveNotes(punishment.getNotes(), resolvedIssuers),
            resolveEvidence(punishment.getEvidence(), resolvedIssuers),
            punishment.getAttachedTicketIds()
        );
    }

    private Map<String, String> resolveIssuersForPunishment(Server server, Punishment punishment) {
        Set<String> ids = collectIssuerIds(punishment);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return issuerNameResolver.batchResolve(ids, server);
    }

    private static String resolveOffenderStatus(Map<String, Object> data) {
        String status = PunishmentData.getStatus(data);
        if (status != null) {
            return status;
        }
        // Backward compat: map legacy offenseLevel to display status
        String offenseLevel = PunishmentData.getOffenseLevel(data);
        if (offenseLevel != null) {
            return switch (offenseLevel.toLowerCase(Locale.ROOT)) {
                case "first" -> "low";
                default -> offenseLevel; // "medium" and "habitual" stay as-is
            };
        }
        return null;
    }

    static Set<String> collectIssuerIds(Punishment punishment) {
        Set<String> ids = new HashSet<>();
        if (punishment.getIssuerId() != null) {
            ids.add(punishment.getIssuerId());
        }
        for (PunishmentModification m : punishment.getModifications()) {
            if (m.issuerId() != null) {
                ids.add(m.issuerId());
            }
        }
        for (PunishmentNote n : punishment.getNotes()) {
            if (n.issuerId() != null) {
                ids.add(n.issuerId());
            }
        }
        for (PunishmentEvidence e : punishment.getEvidence()) {
            if (e.uploadedById() != null) {
                ids.add(e.uploadedById());
            }
        }
        return ids;
    }

    private static List<PunishmentModification> resolveModifications(List<PunishmentModification> modifications, Map<String, String> resolvedIssuers) {
        return modifications.stream().map(m -> new PunishmentModification(
            m.id(), m.type(), m.date(),
            PunishmentMapper.resolveIssuer(m.issuerId(), m.issuerName(), resolvedIssuers),
            m.issuerId(), m.reason(), m.effectiveDuration(), m.appealTicketId(), m.data()
        )).toList();
    }

    private static List<PunishmentNote> resolveNotes(List<PunishmentNote> notes, Map<String, String> resolvedIssuers) {
        return notes.stream().map(n -> new PunishmentNote(
            n.id(), n.text(), n.date(),
            PunishmentMapper.resolveIssuer(n.issuerId(), n.issuerName(), resolvedIssuers),
            n.issuerId()
        )).toList();
    }

    private static List<PunishmentEvidence> resolveEvidence(List<PunishmentEvidence> evidence, Map<String, String> resolvedIssuers) {
        return evidence.stream().map(e -> new PunishmentEvidence(
            e.text(), e.url(), e.type(),
            PunishmentMapper.resolveIssuer(e.uploadedById(), e.uploadedBy(), resolvedIssuers),
            e.uploadedById(), e.uploadedAt(), e.fileName(), e.fileType(), e.fileSize()
        )).toList();
    }

    public Optional<Map<String, Object>> getMinecraftPunishmentById(Server server, String punishmentId) {
        return findPunishmentContext(server, punishmentId).map(context -> {
            List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
            Map<String, String> resolvedIssuers = resolveIssuersForPunishment(server, context.punishment());
            Map<String, Object> result = PunishmentMapper.toPunishmentMap(context.punishment(), types, resolvedIssuers);
            result.put("playerUuid", context.player().getMinecraftUuid().toString());
            result.put("playerName", getLatestUsername(context.player()));
            return result;
        });
    }

    public Optional<PunishmentContext> findPunishmentContext(Server server, String punishmentId) {
        return punishmentRepository.findByPunishmentId(server, punishmentId)
            .flatMap(player -> player.getPunishments()
                .stream()
                .filter(punishment -> punishmentId.equals(punishment.getId()))
                .findFirst()
                .map(punishment -> new PunishmentContext(player, punishment)));
    }

    private String getLatestUsername(Player player) {
        return PlayerDataUtils.extractLatestUsername(player.getUsernames());
    }

    public List<PunishmentSearchResult> searchPunishments(Server server, String searchQuery, boolean activeOnly) {
        List<PunishmentSearchResult> results = new ArrayList<>();

        Pattern pattern = Pattern.compile(Pattern.quote(searchQuery), Pattern.CASE_INSENSITIVE);
        List<Player> players = punishmentRepository.findPlayersWithPunishments(server, 50);

        Set<String> allIssuerIds = new HashSet<>();
        for (Player player : players) {
            for (Punishment punishment : player.getPunishments()) {
                if (punishment.getIssuerId() != null) {
                    allIssuerIds.add(punishment.getIssuerId());
                }
            }
        }
        Map<String, String> resolvedIssuers = allIssuerIds.isEmpty()
                                              ? Map.of()
                                              : issuerNameResolver.batchResolve(allIssuerIds, server);

        for (Player player : players) {
            String username = PlayerDataUtils.extractLatestUsername(player.getUsernames());

            for (Punishment punishment : player.getPunishments()) {
                if (activeOnly && !statusCalculator.isPunishmentActive(punishment)) {
                    continue;
                }

                String resolvedIssuerName = PunishmentMapper.resolveIssuer(
                    punishment.getIssuerId(), punishment.getIssuerName(), resolvedIssuers);

                boolean matches = punishment.getId().contains(searchQuery) ||
                                  pattern.matcher(resolvedIssuerName).find();

                Map<String, Object> pData = punishment.getData();
                if (pData != null) {
                    String reason = PunishmentData.getReason(pData);
                    if (reason != null && pattern.matcher(reason).find()) {
                        matches = true;
                    }
                }

                if (matches) {
                    results.add(new PunishmentSearchResult(
                        punishment.getId(),
                        username,
                        punishment.getTypeOrdinal(),
                        statusCalculator.isPunishmentActive(punishment) ? "Active" : "Inactive",
                        punishment.getIssued()
                    ));

                    if (results.size() >= 20) {
                        return results;
                    }
                }
            }
        }

        return results;
    }

    public List<Map<String, Object>> getRecentPunishments(Server server, int hours) {
        Date cutoff = new Date(System.currentTimeMillis() - (hours * 60L * 60L * 1000L));
        List<Player> players = punishmentRepository.findWithPunishmentsIssuedAfter(server, cutoff);
        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);

        List<Punishment> recentPunishments = new ArrayList<>();
        for (Player player : players) {
            for (Punishment punishment : player.getPunishments()) {
                if (punishment.getIssued() != null && punishment.getIssued().after(cutoff)) {
                    recentPunishments.add(punishment);
                }
            }
        }
        Map<String, String> resolvedIssuers = resolveIssuersForPunishments(server, recentPunishments);

        List<Map<String, Object>> punishments = new ArrayList<>();
        for (Player player : players) {
            String username = getLatestUsername(player);
            for (Punishment punishment : player.getPunishments()) {
                if (punishment.getIssued() == null || !punishment.getIssued().after(cutoff)) {
                    continue;
                }

                Map<String, Object> punishmentMap = PunishmentMapper.toPunishmentMap(punishment, types, resolvedIssuers);
                punishmentMap.put("playerName", username);
                punishmentMap.put("playerUuid", player.getMinecraftUuid().toString());
                punishments.add(punishmentMap);
            }
        }

        punishments.sort((left, right) -> ((Date) right.get("issued")).compareTo((Date) left.get("issued")));
        return punishments.size() > 100 ? punishments.subList(0, 100) : punishments;
    }

    private Map<String, String> resolveIssuersForPunishments(Server server, List<Punishment> punishments) {
        Set<String> ids = new HashSet<>();
        for (Punishment p : punishments) {
            ids.addAll(collectIssuerIds(p));
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return issuerNameResolver.batchResolve(ids, server);
    }

    public List<Map<String, Object>> getLinkedBansForParent(Server server, String parentPunishmentId) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Player> players = punishmentRepository.findByLinkedBanId(server, parentPunishmentId);

        for (Player player : players) {
            String username = PlayerDataUtils.extractLatestUsername(player.getUsernames());

            for (Punishment punishment : player.getPunishments()) {
                if (punishment.getTypeOrdinal() == Punishment.LINKED_BAN_TYPE_ORDINAL &&
                    punishment.getData() != null &&
                    parentPunishmentId.equals(punishment.getData().get("linkedBanId"))) {

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("punishmentId", punishment.getId());
                    entry.put("playerUuid", player.getMinecraftUuid().toString());
                    entry.put("playerName", username);
                    entry.put("active", statusCalculator.isPunishmentActive(punishment));
                    results.add(entry);
                }
            }
        }

        return results;
    }

    public PunishmentPreviewView previewPunishment(Server server, String playerUuid, int typeOrdinal) {
        Player player = playerRepository.findByMinecraftUuid(server, UuidUtil.normalizeUuid(playerUuid)).orElse(null);
        if (player == null) {
            return PunishmentPreviewResponse.error("Player not found");
        }

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        PunishmentType punishmentType = PunishmentTypeIndex.byOrdinal(types).get(typeOrdinal);
        if (punishmentType == null) {
            return PunishmentPreviewResponse.error("Punishment type not found");
        }

        OffenderThresholdSettings thresholds = thresholdSettingsService.getThresholdSettings(server);
        PlayerStatusCalculator.PlayerStatus currentStatus = statusCalculator.calculateStatus(server, player.getPunishments());

        boolean isSocial = punishmentType.isSocial();
        int relevantPoints = isSocial ? currentStatus.socialPoints() : currentStatus.gameplayPoints();
        String offenseLevel = thresholds.getOffenseLevelInternal(relevantPoints, isSocial);
        String socialOffenderLevel = thresholds.getSocialOffenderLevel(currentStatus.socialPoints());
        String gameplayOffenderLevel = thresholds.getGameplayOffenderLevel(currentStatus.gameplayPoints());

        String displayStatus = switch (offenseLevel) {
            case "first" -> "low";
            default -> offenseLevel; // "medium" and "habitual" stay as-is
        };

        PunishmentPreviewResponse.PunishmentPreviewResponseBuilder builder = PunishmentPreviewResponse.builder()
            .status(200)
            .success(true)
            .offenderStatus(displayStatus)
            .socialStatus(socialOffenderLevel)
            .gameplayStatus(gameplayOffenderLevel)
            .socialPoints(currentStatus.socialPoints())
            .gameplayPoints(currentStatus.gameplayPoints())
            .singleSeverityPunishment(punishmentType.isSingleSeverityPunishment())
            .permanentUntilUsernameChange(punishmentType.isPermanentUntilUsernameChange())
            .permanentUntilSkinChange(punishmentType.isPermanentUntilSkinChange())
            .canBeAltBlocking(punishmentType.isCanBeAltBlocking())
            .canBeStatWiping(punishmentType.isCanBeStatWiping())
            .category(punishmentType.getCategory());

        if (punishmentType.isSingleSeverityPunishment()
            || punishmentType.isPermanentUntilUsernameChange()
            || punishmentType.isPermanentUntilSkinChange()) {
            builder.singleSeverity(buildSeverityPreview(punishmentType, "regular", offenseLevel, currentStatus, isSocial, thresholds));
        } else {
            builder.lenient(buildSeverityPreview(punishmentType, "low", offenseLevel, currentStatus, isSocial, thresholds));
            builder.regular(buildSeverityPreview(punishmentType, "regular", offenseLevel, currentStatus, isSocial, thresholds));
            builder.aggravated(buildSeverityPreview(punishmentType, "severe", offenseLevel, currentStatus, isSocial, thresholds));
        }

        return builder.build();
    }

    private PunishmentPreviewResponse.SeverityPreview buildSeverityPreview(
        PunishmentType type,
        String severity,
        String offenseLevel,
        PlayerStatusCalculator.PlayerStatus currentStatus,
        boolean isSocial,
        OffenderThresholdSettings thresholds
    ) {
        int points = type.getPointsForSeverity(severity);
        DurationDetail durationDetail = type.getDurationDetail(severity, offenseLevel);

        PunishmentType defaultType = null;
        if (durationDetail == null || points == 0) {
            defaultType = PunishmentTypeIndex.byOrdinal(DefaultPunishmentTypes.getAll()).get(type.getOrdinal());
        }
        if (durationDetail == null && defaultType != null) {
            durationDetail = defaultType.getDurationDetail(severity, offenseLevel);
        }
        if (points == 0 && defaultType != null) {
            points = defaultType.getPointsForSeverity(severity);
        }

        long durationMs = durationDetail != null ? durationDetail.toMilliseconds() : 0L;
        boolean permanent = durationDetail != null && durationDetail.isPermanent();
        String punishmentType = durationDetail != null
                                ? (durationDetail.isBan() ? "ban" : (durationDetail.isMute() ? "mute" : "kick"))
                                : "unknown";

        int newSocialPoints = currentStatus.socialPoints() + (isSocial ? points : 0);
        int newGameplayPoints = currentStatus.gameplayPoints() + (isSocial ? 0 : points);

        return PunishmentPreviewResponse.SeverityPreview.builder()
            .severity(severity)
            .points(points)
            .durationMs(durationMs)
            .durationFormatted(PunishmentMapper.formatDuration(durationMs, permanent))
            .punishmentType(punishmentType)
            .permanent(permanent)
            .newSocialStatus(thresholds.getSocialOffenderLevel(newSocialPoints))
            .newGameplayStatus(thresholds.getGameplayOffenderLevel(newGameplayPoints))
            .newSocialPoints(newSocialPoints)
            .newGameplayPoints(newGameplayPoints)
            .build();
    }

    public Optional<String> createEvidenceUploadToken(Server server, String punishmentId, String issuerName) {
        return findPunishmentContext(server, punishmentId)
            .map(context -> evidenceUploadTokenService.createToken(
                server,
                punishmentId,
                context.player().getMinecraftUuid().toString(),
                issuerName != null ? issuerName : "Unknown"
            ));
    }

    public Optional<Map<String, Object>> getPublicPunishmentWithAppealEligibility(Server server, String punishmentId) {
        PunishmentContext context = findPunishmentContext(server, punishmentId).orElse(null);
        if (context == null) {
            return Optional.empty();
        }

        PunishmentResponse punishment = toPunishmentResponseWithPlayer(server, context.punishment(), context.player());

        if (punishment.started() == null) {
            return Optional.of(Map.of("error", "This punishment has not been started yet and cannot be appealed at this time."));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", punishment.id());
        response.put("type", punishment.type());
        response.put("issued", punishment.issued());
        response.put("expires", punishment.expires());
        response.put("active", punishment.active());
        response.put("appealable", punishment.isAppealable());
        response.put("playerUuid", context.player().getMinecraftUuid().toString());

        List<Ticket> existingAppeals = ticketRepository.findAppealsByPunishmentId(server, punishmentId);
        if (!existingAppeals.isEmpty()) {
            Ticket latestAppeal = existingAppeals.stream()
                .max((left, right) -> {
                    Date leftDate = left.getCreated();
                    Date rightDate = right.getCreated();
                    if (leftDate == null && rightDate == null) {
                        return 0;
                    }
                    if (leftDate == null) {
                        return -1;
                    }
                    if (rightDate == null) {
                        return 1;
                    }
                    return leftDate.compareTo(rightDate);
                })
                .orElse(existingAppeals.get(0));
            Map<String, Object> existingAppeal = new HashMap<>();
            existingAppeal.put("id", latestAppeal.getId());
            existingAppeal.put("submittedDate", latestAppeal.getCreated());
            String workflowStatus = latestAppeal.getAppealWorkflowStatus() != null
                                    ? latestAppeal.getAppealWorkflowStatus().getId()
                                    : latestAppeal.getStatus() != null ? latestAppeal.getStatus().getId() : "open";
            existingAppeal.put("status", workflowStatus);
            existingAppeal.put("appealWorkflowStatus", workflowStatus);
            response.put("existingAppeal", existingAppeal);
        }

        Optional<PunishmentType> punishmentType = punishmentTypeService.getPunishmentTypeByOrdinal(server, punishment.typeOrdinal());
        response.put("appealForm", punishmentType.map(pt -> pt.getAppealForm()).orElse(null));

        return Optional.of(response);
    }

    public PunishmentResponse getPunishmentById(Server server, String punishmentId) {
        PunishmentContext context = findPunishmentContext(server, punishmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Punishment not found"));
        return toPunishmentResponseWithPlayer(server, context.punishment(), context.player());
    }

    public enum PunishmentOperationStatus {
        SUCCESS,
        NOT_FOUND,
        INVALID_REQUEST,
        NO_OP
    }

    public static Punishment findPunishment(Player player, String punishmentId) {
        if (player.getPunishments().isEmpty()) {
            return null;
        }
        return player.getPunishments()
            .stream()
            .filter(punishment -> punishmentId.equals(punishment.getId()))
            .findFirst()
            .orElse(null);
    }

    public record PunishmentContext(Player player, Punishment punishment) {
    }

    public record UploadedEvidenceItem(String url, String fileName, String fileType, Long fileSize) {
    }

    public record PunishmentOperationResult(
        PunishmentOperationStatus status,
        String message,
        boolean success,
        int affectedCount
    ) {
    }

}
