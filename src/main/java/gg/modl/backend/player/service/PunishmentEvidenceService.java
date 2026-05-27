package gg.modl.backend.player.service;

import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.PunishmentMongoRepository;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.dto.request.AddEvidenceRequest;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentContext;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.player.service.PunishmentQueryService.UploadedEvidenceItem;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.infrastructure.util.IdGenerator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentEvidenceService {
    private final PlayerMongoRepository playerRepository;
    private final PunishmentMongoRepository punishmentRepository;
    private final PunishmentQueryService punishmentQueryService;

    public PunishmentOperationResult addEvidence(Server server, String punishmentId, String evidenceUrl, String issuerName, String issuerId) {
        PunishmentContext context = punishmentQueryService.findPunishmentContext(server, punishmentId).orElse(null);
        if (context == null) {
            return new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0);
        }

        String resolvedIssuerName = issuerId != null ? null : issuerName;
        Date now = new Date();
        context.punishment().getEvidence().add(new PunishmentEvidence(
            null,
            evidenceUrl,
            "url",
            resolvedIssuerName,
            issuerId,
            now,
            null,
            null,
            null
        ));
        context.punishment().getNotes().add(new PunishmentNote(
            IdGenerator.generateShortId(),
            "added evidence",
            now,
            resolvedIssuerName,
            issuerId
        ));
        punishmentRepository.replacePunishments(server, context.player());

        return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Evidence added", true, 1);
    }

    public Player addEvidence(Server server, UUID playerUuid, String punishmentId, AddEvidenceRequest request) {
        Player player = playerRepository.findByMinecraftUuid(server, playerUuid.toString())
            .orElseThrow(() -> new ResourceNotFoundException("Player not found"));

        Punishment punishment = findPunishment(player, punishmentId);
        if (punishment == null) {
            throw new ResourceNotFoundException("Punishment not found");
        }

        String evIssuerId = request.issuerId();
        String evIssuerName = evIssuerId != null ? null : (request.issuerName() != null ? request.issuerName() : "System");

        punishment.getEvidence().add(new PunishmentEvidence(
            request.text(),
            request.url(),
            request.type(),
            evIssuerName,
            evIssuerId,
            new Date(),
            request.fileName(),
            request.fileType(),
            request.fileSize()
        ));

        punishmentRepository.replacePunishments(server, player);
        return player;
    }

    private Punishment findPunishment(Player player, String punishmentId) {
        return PunishmentQueryService.findPunishment(player, punishmentId);
    }

    public PunishmentOperationResult addUploadedEvidence(Server server, String punishmentId, String issuerName, String issuerId, List<UploadedEvidenceItem> evidenceItems) {
        PunishmentContext context = punishmentQueryService.findPunishmentContext(server, punishmentId).orElse(null);
        if (context == null) {
            return new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0);
        }

        String resolvedIssuerName = issuerId != null ? null : issuerName;
        Date now = new Date();
        for (UploadedEvidenceItem evidenceItem : evidenceItems) {
            context.punishment().getEvidence().add(new PunishmentEvidence(
                null,
                evidenceItem.url(),
                "file",
                resolvedIssuerName,
                issuerId,
                now,
                evidenceItem.fileName(),
                evidenceItem.fileType(),
                evidenceItem.fileSize()
            ));
        }
        context.punishment().getNotes().add(new PunishmentNote(
            IdGenerator.generateShortId(),
            "uploaded " + evidenceItems.size() + " evidence file(s)",
            now,
            resolvedIssuerName,
            issuerId
        ));
        punishmentRepository.replacePunishments(server, context.player());

        return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Evidence uploaded successfully", true, evidenceItems.size());
    }

    public PunishmentOperationResult addPunishmentNote(Server server, String punishmentId, String text, String issuerName, String issuerId) {
        PunishmentContext context = punishmentQueryService.findPunishmentContext(server, punishmentId).orElse(null);
        if (context == null) {
            return new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0);
        }

        String resolvedIssuerName = issuerId != null ? null : issuerName;
        context.punishment().getNotes().add(new PunishmentNote(IdGenerator.generateShortId(), text, new Date(), resolvedIssuerName, issuerId));
        punishmentRepository.replacePunishments(server, context.player());

        return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Note added", true, 1);
    }

    public Player addPunishmentNote(Server server, UUID playerUuid, String punishmentId, String text, String issuerName, String issuerId) {
        Player player = playerRepository.findByMinecraftUuid(server, playerUuid.toString())
            .orElseThrow(() -> new ResourceNotFoundException("Player not found"));

        Punishment punishment = findPunishment(player, punishmentId);
        if (punishment == null) {
            throw new ResourceNotFoundException("Punishment not found");
        }

        String resolvedIssuerName = issuerId != null ? null : issuerName;
        punishment.getNotes().add(new PunishmentNote(IdGenerator.generateShortId(), text, new Date(), resolvedIssuerName, issuerId));
        punishmentRepository.replacePunishments(server, player);
        return player;
    }
}
