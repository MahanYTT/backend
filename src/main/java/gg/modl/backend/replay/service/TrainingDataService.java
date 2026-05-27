package gg.modl.backend.replay.service;

import gg.modl.backend.database.mongo.repository.TrainingSegmentRepository;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayLabel;
import gg.modl.backend.replay.data.TrainingSegmentDocument;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.service.S3StorageService;
import gg.modl.minecraft.replay.ReplayReader;
import gg.modl.minecraft.replay.ReplayWriter;
import gg.modl.minecraft.replay.format.ReplayEvent;
import gg.modl.minecraft.replay.format.ReplayHeader;
import gg.modl.minecraft.replay.format.events.PlayerMoveEvent;
import gg.modl.minecraft.replay.format.events.PlayerSpawnEvent;
import gg.modl.minecraft.replay.util.BlockSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.Binary;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrainingDataService {
    private final S3StorageService s3StorageService;
    private final TrainingSegmentRepository trainingSegmentRepository;

    private static final int BLOCK_FILTER_RADIUS = 16;
    private static final int SUPPORTED_TRAINING_REPLAY_FORMAT_VERSION = 4;

    /**
     * Exceptions are caught and logged; callers are not notified of failures.
     */
    @Async
    public void generateSegmentsAsync(Server server, ReplayDocument doc, List<ReplayLabel> labels) {
        try {
            generateSegments(server, doc, labels);
        } catch (Exception e) {
            log.error("Failed to generate training segments for replay {} on server {}",
                doc.getId(), server.getDatabaseName(), e);
        }
    }

    private void generateSegments(Server server, ReplayDocument doc, List<ReplayLabel> labels) throws IOException {
        byte[] replayBytes = s3StorageService.downloadBytes(doc.getStorageKey());

        ReplayHeader header;
        List<BlockSnapshot> snapshot;
        List<ReplayEvent> allEvents = new ArrayList<>();

        try (ReplayReader reader = new ReplayReader(new ByteArrayInputStream(replayBytes))) {
            header = reader.readHeader();
            if (!supportsTrainingReplayFormat(header)) {
                log.warn(
                    "Skipping training segment generation for replay {} on server {}: unsupported replay format version {}",
                    doc.getId(), server.getDatabaseName(), header.getVersion()
                );
                return;
            }

            snapshot = reader.readSnapshot();

            ReplayEvent event;
            while ((event = reader.readEvent()) != null) {
                allEvents.add(event);
            }
        }

        for (ReplayLabel label : labels) {
            if ("unsure".equals(label.getVerdict())) {
                continue;
            }

            UUID playerUuid = UUID.fromString(label.getUuid());

            if ("cheating".equals(label.getVerdict())) {
                if (label.getCheats() == null) {
                    continue;
                }
                for (ReplayLabel.CheatDetail cheat : label.getCheats()) {
                    if (cheat.getTimeRanges() == null) {
                        continue;
                    }
                    for (ReplayLabel.TimeRange range : cheat.getTimeRanges()) {
                        TrainingSegmentDocument segment = buildSegment(
                            header, snapshot, allEvents,
                            server, doc, label, playerUuid,
                            cheat.getType(), range.getStartMs(), range.getEndMs()
                        );
                        trainingSegmentRepository.save(segment);
                    }
                }
            } else if ("legit".equals(label.getVerdict())) {
                long maxMs = 0;
                for (ReplayEvent event : allEvents) {
                    if (event.getTimestampDeltaMs() > maxMs) {
                        maxMs = event.getTimestampDeltaMs();
                    }
                }

                TrainingSegmentDocument segment = buildSegment(
                    header, snapshot, allEvents,
                    server, doc, label, playerUuid,
                    null, 0, maxMs
                );
                trainingSegmentRepository.save(segment);
            }
        }

        log.debug("Generated training segments for replay {} on server {}", doc.getId(), server.getDatabaseName());
    }

    static boolean supportsTrainingReplayFormat(ReplayHeader header) {
        return header.getVersion() == SUPPORTED_TRAINING_REPLAY_FORMAT_VERSION;
    }

    private TrainingSegmentDocument buildSegment(
        ReplayHeader header,
        List<BlockSnapshot> snapshot,
        List<ReplayEvent> allEvents,
        Server server,
        ReplayDocument doc,
        ReplayLabel label,
        UUID playerUuid,
        String cheatType,
        long startMs,
        long endMs
    ) throws IOException {
        List<ReplayEvent> segmentEvents = new ArrayList<>();
        for (ReplayEvent event : allEvents) {
            if (event.getTimestampDeltaMs() >= startMs && event.getTimestampDeltaMs() <= endMs) {
                segmentEvents.add(event);
            }
        }

        Set<Long> playerBlockPositions = new HashSet<>();
        for (ReplayEvent event : segmentEvents) {
            if (event instanceof PlayerMoveEvent move && move.getUuid().equals(playerUuid)) {
                addBlockPosition(playerBlockPositions, move.getX(), move.getZ());
            } else if (event instanceof PlayerSpawnEvent spawn && spawn.getUuid().equals(playerUuid)) {
                addBlockPosition(playerBlockPositions, spawn.getX(), spawn.getZ());
            }
        }

        // Seed the player's last known position before the segment window opens.
        for (ReplayEvent event : allEvents) {
            if (event.getTimestampDeltaMs() > startMs) {
                break;
            }
            if (event instanceof PlayerSpawnEvent spawn && spawn.getUuid().equals(playerUuid)) {
                addBlockPosition(playerBlockPositions, spawn.getX(), spawn.getZ());
            } else if (event instanceof PlayerMoveEvent move && move.getUuid().equals(playerUuid)) {
                addBlockPosition(playerBlockPositions, move.getX(), move.getZ());
            }
        }

        List<BlockSnapshot> filteredBlocks;
        if (playerBlockPositions.isEmpty()) {
            filteredBlocks = snapshot;
        } else {
            filteredBlocks = new ArrayList<>();
            for (BlockSnapshot block : snapshot) {
                if (isWithinRadius(block.getX(), block.getZ(), playerBlockPositions)) {
                    filteredBlocks.add(block);
                }
            }
        }

        long timestampOffset = startMs;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ReplayWriter writer = new ReplayWriter(baos)) {
            writer.writeHeader(header);
            writer.writeSnapshot(filteredBlocks);
            for (ReplayEvent event : segmentEvents) {
                writer.writeEvent(event, (int) timestampOffset);
            }
            writer.flush();
        }

        TrainingSegmentDocument segment = new TrainingSegmentDocument();
        segment.setReplayId(doc.getId());
        segment.setServerName(server.getServerName());
        segment.setServerDatabaseName(server.getDatabaseName());
        segment.setPlayerUuid(playerUuid.toString());
        segment.setPlayerName(label.getPlayerName());
        segment.setVerdict(label.getVerdict());
        segment.setCheatType(cheatType);
        segment.setConfidence(label.getConfidence());
        segment.setNotes(label.getNotes());
        segment.setStartMs(startMs);
        segment.setEndMs(endMs);
        segment.setMcVersion(doc.getMcVersion());
        segment.setSegmentBinary(new Binary(baos.toByteArray()));
        segment.setCreatedAt(new Date());

        return segment;
    }

    private void addBlockPosition(Set<Long> positions, float x, float z) {
        positions.add(packXZ((int) Math.floor(x), (int) Math.floor(z)));
    }

    private boolean isWithinRadius(int blockX, int blockZ, Set<Long> playerBlockPositions) {
        for (long packed : playerBlockPositions) {
            int px = unpackX(packed);
            int pz = unpackZ(packed);
            if (Math.abs(blockX - px) <= BLOCK_FILTER_RADIUS && Math.abs(blockZ - pz) <= BLOCK_FILTER_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }
}
