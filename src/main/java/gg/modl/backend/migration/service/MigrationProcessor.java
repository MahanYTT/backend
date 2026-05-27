package gg.modl.backend.migration.service;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.migration.dto.UpdateProgressRequest;
import gg.modl.backend.migration.validation.MigrationValidator;
import gg.modl.backend.player.PlayerDocumentIdGenerator;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.server.data.Server;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationProcessor {
    private final PlayerMongoRepository playerRepository;
    private final MigrationService migrationService;
    private final MigrationValidator validator;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 500;
    private static final int PROGRESS_UPDATE_INTERVAL = 1000;
    private static final int MAX_JSON_NESTING_DEPTH = 100;
    private static final int MAX_JSON_STRING_LENGTH = 1_000_000;

    @Async
    public void processFileAsync(Server server, Path filePath) {
        try {
            processFile(server, filePath);
        } catch (Exception e) {
            log.error("Async migration processing failed", e);
        }
    }

    public void processFile(Server server, Path filePath) {
        int recordsProcessed = 0;
        int recordsSkipped = 0;

        try {
            migrationService.updateProgress(server, new UpdateProgressRequest(
                "processing_data",
                "Reading and validating migration file...",
                0, 0, null
            ));

            ObjectMapper constrainedMapper = objectMapper.copy();
            constrainedMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                .maxStringLength(MAX_JSON_STRING_LENGTH)
                .build());
            Map<String, Object> migrationData = constrainedMapper.readValue(filePath.toFile(), Map.class);

            MigrationValidator.ValidationResult validation = validator.validateMigrationData(migrationData);
            if (!validation.valid()) {
                migrationService.updateProgress(server, new UpdateProgressRequest(
                    "failed",
                    validation.error(),
                    0, 0, null
                ));
                return;
            }

            int totalRecords = validation.playerCount();
            List<?> players = (List<?>) migrationData.get("players");

            migrationService.updateProgress(server, new UpdateProgressRequest(
                "processing_data",
                "Processing " + totalRecords + " player records...",
                0, 0, totalRecords
            ));

            List<Map<?, ?>> batch = new ArrayList<>();

            for (int i = 0; i < players.size(); i++) {
                Object playerObj = players.get(i);

                if (!(playerObj instanceof Map<?, ?> playerMap)) {
                    recordsSkipped++;
                    continue;
                }
                batch.add(playerMap);

                if (batch.size() >= BATCH_SIZE || i == players.size() - 1) {
                    int[] results = processBatch(server, batch);
                    recordsProcessed += results[0];
                    recordsSkipped += results[1];
                    batch.clear();

                    if (recordsProcessed % PROGRESS_UPDATE_INTERVAL == 0 || i == players.size() - 1) {
                        migrationService.updateProgress(server, new UpdateProgressRequest(
                            "processing_data",
                            "Processing player records... (" + recordsProcessed + "/" + totalRecords + ")",
                            recordsProcessed, recordsSkipped, totalRecords
                        ));
                    }
                }
            }

            migrationService.updateProgress(server, new UpdateProgressRequest(
                "completed",
                "Migration completed successfully",
                recordsProcessed, recordsSkipped, totalRecords
            ));

        } catch (Exception e) {
            log.error("Error processing migration file", e);
            migrationService.updateProgress(server, new UpdateProgressRequest(
                "failed",
                "Migration failed: " + e.getMessage(),
                recordsProcessed, recordsSkipped, null
            ));
        } finally {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                log.warn("Failed to delete migration file: {}", filePath, e);
            }
        }
    }

    private int[] processBatch(Server server, List<Map<?, ?>> batch) {
        int processed = 0;
        int skipped = 0;

        List<String> uuids = new ArrayList<>();
        Map<String, Map<?, ?>> playerDataMap = new HashMap<>();

        for (Map<?, ?> playerMap : batch) {
            Object uuidObj = playerMap.get("minecraftUuid");
            if (uuidObj == null || !(uuidObj instanceof String uuidStr)) {
                skipped++;
                continue;
            }

            String uuid = validator.normalizeUuid(uuidStr);
            if (!validator.isValidUuid(uuid)) {
                skipped++;
                continue;
            }

            uuids.add(uuid);
            playerDataMap.put(uuid, playerMap);
        }

        if (uuids.isEmpty()) {
            return new int[]{0, skipped};
        }

        List<Player> existingPlayers = playerRepository.findByMinecraftUuids(server,
            uuids.stream().map(UUID::fromString).toList());
        Map<String, Player> existingMap = new HashMap<>();
        for (Player p : existingPlayers) {
            existingMap.put(p.getMinecraftUuid().toString(), p);
        }

        List<Player> toInsert = new ArrayList<>();
        Map<UUID, Update> mergeUpdates = new HashMap<>();

        for (String uuid : uuids) {
            try {
                Map<?, ?> playerMap = playerDataMap.get(uuid);
                Player existing = existingMap.get(uuid);

                if (existing != null) {
                    Update update = buildMergeUpdate(existing, playerMap);
                    if (update != null) {
                        mergeUpdates.put(UUID.fromString(uuid), update);
                    }
                } else {
                    Player newPlayer = buildNewPlayer(uuid, playerMap);
                    if (newPlayer != null) {
                        toInsert.add(newPlayer);
                    }
                }
                processed++;
            } catch (Exception e) {
                log.warn("Error processing player {}", uuid, e);
                skipped++;
            }
        }

        if (!toInsert.isEmpty()) {
            playerRepository.insertAll(server, toInsert);
        }

        if (!mergeUpdates.isEmpty()) {
            playerRepository.bulkMergeByUuid(server, mergeUpdates);
        }

        return new int[]{processed, skipped};
    }

    private Player buildNewPlayer(String uuid, Map<?, ?> data) {
        try {
            return Player.builder()
                .id(PlayerDocumentIdGenerator.generate())
                .minecraftUuid(UUID.fromString(uuid))
                .usernames(parseUsernames(data.get("usernames")))
                .notes(parseNotes(data.get("notes")))
                .ipAddresses(parseIpAddresses(data.get("ipAddresses")))
                .punishments(parsePunishments(data.get("punishments")))
                .data(parseData(data.get("data")))
                .build();
        } catch (Exception e) {
            log.warn("Error building new player for UUID {}", uuid, e);
            return null;
        }
    }

    private List<IPEntry> parseIpAddresses(Object data) {
        List<IPEntry> result = new ArrayList<>();
        if (!(data instanceof List<?> dataList)) {
            return result;
        }

        for (Object item : dataList) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            String ipAddress = (String) map.get("ipAddress");
            if (!validator.isValidIpAddress(ipAddress)) {
                continue;
            }

            Date firstLogin = validator.parseDate(map.get("firstLogin"));
            if (firstLogin == null) {
                firstLogin = new Date();
            }

            List<Date> logins = new ArrayList<>();
            Object loginsObj = map.get("logins");
            if (loginsObj instanceof List<?> loginsList) {
                for (Object loginObj : loginsList) {
                    Date login = validator.parseDate(loginObj);
                    if (login != null) {
                        logins.add(login);
                    }
                }
            }

            result.add(IPEntry.builder()
                .ipAddress(ipAddress)
                .country(validator.sanitizeString((String) map.get("country"), 100))
                .region(validator.sanitizeString((String) map.get("region"), 100))
                .asn(validator.sanitizeString((String) map.get("asn"), 100))
                .proxy(Boolean.TRUE.equals(map.get("proxy")))
                .hosting(Boolean.TRUE.equals(map.get("hosting")))
                .firstLogin(firstLogin)
                .logins(logins)
                .build());
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseData(Object data) {
        if (data instanceof Map<?, ?> dataMap) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        return new HashMap<>();
    }

    private Update buildMergeUpdate(Player existing, Map<?, ?> newData) {
        Update update = new Update();
        boolean hasChanges = false;

        List<UsernameEntry> newUsernames = parseUsernames(newData.get("usernames"));
        if (!newUsernames.isEmpty()) {
            Set<String> existingNames = new HashSet<>();
            for (UsernameEntry u : existing.getUsernames()) {
                existingNames.add(u.username());
            }
            for (UsernameEntry u : newUsernames) {
                if (!existingNames.contains(u.username())) {
                    update.push("usernames", u);
                    hasChanges = true;
                }
            }
        }

        List<NoteEntry> newNotes = parseNotes(newData.get("notes"));
        if (!newNotes.isEmpty()) {
            for (NoteEntry note : newNotes) {
                update.push("notes", note);
                hasChanges = true;
            }
        }

        List<Punishment> newPunishments = parsePunishments(newData.get("punishments"));
        if (!newPunishments.isEmpty()) {
            Set<String> existingIds = new HashSet<>();
            for (Punishment p : existing.getPunishments()) {
                existingIds.add(p.getId());
            }
            for (Punishment p : newPunishments) {
                if (!existingIds.contains(p.getId())) {
                    update.push("punishments", p);
                    hasChanges = true;
                }
            }
        }

        return hasChanges ? update : null;
    }

    private List<UsernameEntry> parseUsernames(Object data) {
        List<UsernameEntry> result = new ArrayList<>();
        if (!(data instanceof List<?> dataList)) {
            return result;
        }

        for (Object item : dataList) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            String username = validator.sanitizeString((String) map.get("username"), 100);
            Date date = validator.parseDate(map.get("date"));

            if (username != null && !username.isBlank() && date != null) {
                result.add(new UsernameEntry(username, date));
            }
        }

        return result;
    }

    private List<NoteEntry> parseNotes(Object data) {
        List<NoteEntry> result = new ArrayList<>();
        if (!(data instanceof List<?> dataList)) {
            return result;
        }

        for (Object item : dataList) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            String text = validator.sanitizeString((String) map.get("text"), 5000);
            Date date = validator.parseDate(map.get("date"));
            String issuerName = validator.sanitizeString((String) map.get("issuerName"), 100);

            if (text != null && date != null && issuerName != null) {
                result.add(new NoteEntry(
                    UUID.randomUUID().toString(),
                    text,
                    date,
                    issuerName,
                    null
                ));
            }
        }

        return result;
    }

    private List<Punishment> parsePunishments(Object data) {
        List<Punishment> result = new ArrayList<>();
        if (!(data instanceof List<?> dataList)) {
            return result;
        }

        for (Object item : dataList) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            String id = (String) map.get("id");
            if (id == null) {
                id = UUID.randomUUID().toString();
            }

            Date issued = validator.parseDate(map.get("issued"));
            if (issued == null) {
                continue;
            }

            String issuerName = validator.sanitizeString((String) map.get("issuerName"), 100);
            if (issuerName == null) {
                issuerName = "Unknown";
            }

            Object typeOrdinalObj = map.get("typeOrdinal");
            int typeOrdinal = 0;
            if (typeOrdinalObj instanceof Number typeOrdinalNum) {
                typeOrdinal = typeOrdinalNum.intValue();
            } else if (typeOrdinalObj instanceof String typeOrdinalString) {
                try {
                    typeOrdinal = Integer.parseInt(typeOrdinalString);
                } catch (NumberFormatException ignored) {
                    typeOrdinal = 0;
                }
            }

            List<PunishmentNote> notes = new ArrayList<>();
            Object notesObj = map.get("notes");
            if (notesObj instanceof List<?> notesList) {
                for (Object noteObj : notesList) {
                    if (noteObj instanceof Map<?, ?> noteMap) {
                        String text = validator.sanitizeString((String) noteMap.get("text"), 5000);
                        Date date = validator.parseDate(noteMap.get("date"));
                        String noteIssuer = validator.sanitizeString((String) noteMap.get("issuerName"), 100);

                        if (text != null && date != null) {
                            notes.add(new PunishmentNote(IdGenerator.generateShortId(), text, date, noteIssuer != null ? noteIssuer : "Unknown", null));
                        }
                    }
                }
            }

            List<PunishmentEvidence> evidence = new ArrayList<>();

            List<PunishmentModification> modifications = new ArrayList<>();

            List<String> attachedTicketIds = new ArrayList<>();
            Object ticketIdsObj = map.get("attachedTicketIds");
            if (ticketIdsObj instanceof List<?> ticketIdsList) {
                for (Object ticketId : ticketIdsList) {
                    if (ticketId instanceof String ticketIdStr) {
                        attachedTicketIds.add(ticketIdStr);
                    }
                }
            }

            Map<String, Object> punishmentData = new HashMap<>();
            Object dataObj = map.get("data");
            if (dataObj instanceof Map<?, ?> dataMap) {
                for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
                    if (entry.getKey() instanceof String key) {
                        punishmentData.put(key, entry.getValue());
                    }
                }
            }

            String reason = validator.sanitizeString((String) map.get("reason"), 1000);
            if (reason != null && !reason.isBlank()) {
                punishmentData.put("reason", reason);
            }

            Object durationObj = map.get("duration");
            if (durationObj instanceof Number durationNum) {
                punishmentData.put("duration", durationNum.longValue());
            }

            Date started = validator.parseDate(map.get("started"));

            Punishment punishment = new Punishment(
                id,
                typeOrdinal,
                issuerName,
                null,
                issued,
                started,
                modifications,
                notes,
                evidence,
                attachedTicketIds,
                punishmentData.isEmpty() ? null : punishmentData
            );

            result.add(punishment);
        }

        return result;
    }
}
