package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.PunishmentMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.storage.service.EvidenceUploadTokenService;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketStatus;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PunishmentQueryServiceTest {

    @Test
    void publicAppealEligibilityReturnsPlayerUuidAndLatestExistingAppealDetails() {
        PlayerMongoRepository playerRepository = mock(PlayerMongoRepository.class);
        PunishmentMongoRepository punishmentRepository = mock(PunishmentMongoRepository.class);
        PlayerStatusCalculator statusCalculator = mock(PlayerStatusCalculator.class);
        PunishmentTypeService punishmentTypeService = mock(PunishmentTypeService.class);
        TicketMongoRepository ticketRepository = mock(TicketMongoRepository.class);
        PunishmentQueryService service = new PunishmentQueryService(
            playerRepository,
            punishmentRepository,
            statusCalculator,
            punishmentTypeService,
            mock(OffenderThresholdSettingsService.class),
            mock(EvidenceUploadTokenService.class),
            mock(IssuerNameResolver.class),
            mock(StaffMongoRepository.class),
            ticketRepository
        );
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        UUID playerUuid = UUID.randomUUID();
        Date issued = new Date(1_700_000_000_000L);
        Date started = new Date(1_700_000_001_000L);
        Punishment punishment = new Punishment();
        punishment.setId("punishment-1");
        punishment.setTypeOrdinal(2);
        punishment.setIssued(issued);
        punishment.setStarted(started);
        punishment.setModifications(new ArrayList<>());
        punishment.setNotes(new ArrayList<>());
        punishment.setEvidence(new ArrayList<>());
        punishment.setAttachedTicketIds(new ArrayList<>());
        punishment.setData(Map.of());
        Player player = Player.builder()
            .minecraftUuid(playerUuid)
            .punishments(new ArrayList<>(List.of(punishment)))
            .build();
        Ticket olderAppeal = Ticket.builder()
            .id("APPEAL-111111")
            .created(new Date(1_700_000_002_000L))
            .status(TicketStatus.OPEN)
            .appealWorkflowStatus(AppealWorkflowStatus.OPEN)
            .build();
        Ticket latestAppeal = Ticket.builder()
            .id("APPEAL-222222")
            .created(new Date(1_700_000_003_000L))
            .status(TicketStatus.OPEN)
            .appealWorkflowStatus(AppealWorkflowStatus.REJECTED)
            .build();
        PunishmentType punishmentType = new PunishmentType();

        when(punishmentRepository.findByPunishmentId(server, "punishment-1")).thenReturn(Optional.of(player));
        when(statusCalculator.isPunishmentActive(punishment)).thenReturn(true);
        when(statusCalculator.getEffectiveExpiry(punishment)).thenReturn(null);
        when(punishmentTypeService.getPunishmentTypeName(server, 2)).thenReturn("Ban");
        when(punishmentTypeService.isAppealable(server, 2)).thenReturn(true);
        when(punishmentTypeService.getPunishmentTypeByOrdinal(server, 2)).thenReturn(Optional.of(punishmentType));
        when(ticketRepository.findAppealsByPunishmentId(server, "punishment-1")).thenReturn(List.of(olderAppeal, latestAppeal));

        Map<String, Object> response = service.getPublicPunishmentWithAppealEligibility(server, "punishment-1").orElseThrow();

        assertEquals(playerUuid.toString(), response.get("playerUuid"));
        assertTrue(response.get("existingAppeal") instanceof Map<?, ?>);
        Map<?, ?> existingAppeal = (Map<?, ?>) response.get("existingAppeal");
        assertEquals("APPEAL-222222", existingAppeal.get("id"));
        assertEquals(latestAppeal.getCreated(), existingAppeal.get("submittedDate"));
        assertEquals("rejected", existingAppeal.get("status"));
        assertEquals("rejected", existingAppeal.get("appealWorkflowStatus"));
    }

    @Test
    void previewPunishmentLowercasesUuidBeforeQueryingPlayerRepository() {
        PlayerMongoRepository playerRepository = mock(PlayerMongoRepository.class);
        PunishmentQueryService service = new PunishmentQueryService(
            playerRepository,
            mock(PunishmentMongoRepository.class),
            mock(PlayerStatusCalculator.class),
            mock(PunishmentTypeService.class),
            mock(OffenderThresholdSettingsService.class),
            mock(EvidenceUploadTokenService.class),
            mock(IssuerNameResolver.class),
            mock(StaffMongoRepository.class),
            mock(TicketMongoRepository.class)
        );
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        when(playerRepository.findByMinecraftUuid(eq(server), eq("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
            .thenReturn(Optional.empty());

        service.previewPunishment(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", 1);

        verify(playerRepository).findByMinecraftUuid(server, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }
}
