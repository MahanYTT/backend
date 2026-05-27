package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.config.ModlProperties;
import gg.modl.backend.database.mongo.repository.ServerInstanceSnapshotMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.player.controller.v2.MinecraftStartupController.StartupRequest;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.StartupResponse;
import gg.modl.proto.modl.v1.Topic;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinecraftStartupService {
    private static final List<Topic> MINECRAFT_STARTUP_TOPICS = Arrays.asList(
        Topic.TOPIC_MINECRAFT_PERMISSIONS,
        Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES
    );

    private final ModlProperties modlProperties;
    private final RealtimeProperties realtimeProperties;
    private final ServerMongoRepository serverRepository;
    private final ServerInstanceSnapshotMongoRepository serverInstanceSnapshotRepository;

    public StartupResponse handleStartup(Server server, StartupRequest request, String clientIp) {
        return handleStartup(
            server,
            request.serverVersion(),
            request.platformType(),
            request.pluginVersion(),
            request.maxPlayers(),
            request.serverName(),
            clientIp
        );
    }

    public StartupResponse handleStartup(
        Server server,
        String serverVersion,
        String platformType,
        String pluginVersion,
        int maxPlayers,
        String serverName,
        String clientIp
    ) {
        Instant now = Instant.now();

        String domain = server.getCustomDomainOverride();
        if (domain == null || domain.isBlank()) {
            domain = server.getCustomDomain() + "." + modlProperties.getDomain();
        }
        String panelUrl = "https://" + domain;

        serverRepository.updateFirst(
            Query.query(Criteria.where("_id").is(server.getId())),
            new Update().set("lastActivityAt", Date.from(now))
        );

        try {
            long epochSeconds = now.getEpochSecond();
            Date fiveMinBoundary = Date.from(Instant.ofEpochSecond((epochSeconds / 300) * 300));
            serverInstanceSnapshotRepository.upsertServerEntry(
                fiveMinBoundary,
                server.getId(),
                serverName,
                0,
                platformType,
                serverVersion,
                clientIp,
                pluginVersion,
                Date.from(now)
            );
        } catch (Exception e) {
            log.warn("Failed to upsert server instance snapshot during startup", e);
        }

        String realtimeUrl = normalizedRealtimeUrl();
        boolean realtimeEnabled = realtimeProperties.isEnabled() && realtimeUrl != null;

        StartupResponse.Builder builder = StartupResponse.newBuilder()
            .setPanelUrl(panelUrl)
            .setTimestamp(now.toString())
            .setServerInstanceId(UUID.randomUUID().toString())
            .setRealtimeEnabled(realtimeEnabled)
            .setRealtimeProtocolVersion(realtimeProperties.getProtocolVersion());

        if (realtimeEnabled) {
            builder.setRealtimeUrl(realtimeUrl);
            for (Topic topic : MINECRAFT_STARTUP_TOPICS) {
                builder.addRealtimeTopics(topic.name());
            }
        }

        return builder.build();
    }

    private String normalizedRealtimeUrl() {
        String publicUrl = realtimeProperties.getPublicUrl();
        if (publicUrl == null || publicUrl.isBlank()) {
            return null;
        }
        return publicUrl.trim();
    }
}
