package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlayerLookupServiceTest {
    private PlayerLookupService service;
    @Mock private PlayerMongoRepository playerRepository;
    @Mock private PunishmentTypeService punishmentTypeService;
    @Mock private MojangApiService mojangApiService;
    @Mock private PlayerStatusCalculator playerStatusCalculator;
    @Mock private IssuerNameResolver issuerNameResolver;
    @Mock private StaffMongoRepository staffRepository;
    @Mock private PlayerService playerService;
    private Server server;

    @BeforeEach
    void setUp() {
        service = new PlayerLookupService(
            playerRepository,
            punishmentTypeService,
            mojangApiService,
            playerStatusCalculator,
            issuerNameResolver,
            staffRepository,
            playerService
        );
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);
    }

    @Test
    void getPlayerByUuidLowercasesUuidBeforeQueryingPlayerRepository() {
        when(playerRepository.findByMinecraftUuid(eq(server), eq("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")))
            .thenReturn(java.util.Optional.empty());

        service.getPlayerByUuid(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", null, null);

        verify(playerRepository).findByMinecraftUuid(server, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }

    @Test
    void toPlayerProfileAppliesLimitsToEmbeddedCollectionsAndKeepsTotals() {
        Player player = Player.builder()
            .id("player-id")
            .minecraftUuid(UUID.randomUUID())
            .usernames(List.of(new UsernameEntry("Byteful", new Date(1_000L))))
            .notes(List.of(
                note("note-1"),
                note("note-2"),
                note("note-3"),
                note("note-4")
            ))
            .punishments(List.of(
                punishment("punishment-1"),
                punishment("punishment-2"),
                punishment("punishment-3"),
                punishment("punishment-4"),
                punishment("punishment-5")
            ))
            .data(new HashMap<>())
            .build();

        Map<String, Object> profile = service.toPlayerProfile(server, player, List.<PunishmentType>of(), 3, 2);

        List<?> punishments = (List<?>) profile.get("punishments");
        List<?> notes = (List<?>) profile.get("notes");
        assertEquals(3, punishments.size());
        assertEquals(2, notes.size());
        assertEquals(5, profile.get("punishmentCount"));
        assertEquals(4, profile.get("noteCount"));
        assertEquals("punishment-1", ((Map<?, ?>) punishments.get(0)).get("id"));
        assertEquals("punishment-3", ((Map<?, ?>) punishments.get(2)).get("id"));
        assertEquals("note-1", ((Map<?, ?>) notes.get(0)).get("id"));
        assertEquals("note-2", ((Map<?, ?>) notes.get(1)).get("id"));
    }

    private NoteEntry note(String id) {
        return NoteEntry.builder()
            .id(id)
            .text(id + " text")
            .date(new Date(2_000L))
            .issuerName("Mod")
            .build();
    }

    private Punishment punishment(String id) {
        return new Punishment(
            id,
            1,
            "Mod",
            null,
            new Date(3_000L),
            null,
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>(),
            new HashMap<>()
        );
    }
}
