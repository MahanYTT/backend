package gg.modl.backend.log.service;

import gg.modl.backend.database.mongo.repository.ServerLogMongoRepository;
import gg.modl.backend.log.data.SystemLog;
import gg.modl.backend.log.dto.response.SystemLogResponse;
import gg.modl.backend.server.data.Server;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {
    private final ServerLogMongoRepository serverLogRepository;

    private static final int MAX_LIMIT = 500;

    public @NotNull List<SystemLogResponse> getLogs(@NotNull Server server, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<SystemLog> logs = serverLogRepository.findRecent(server, safeLimit);

        return logs.stream()
            .map(l -> new SystemLogResponse(
                l.getId(),
                l.getDescription(),
                l.getLevel(),
                l.getSource(),
                l.getCreated()
            ))
            .toList();
    }

    public @NotNull SystemLog createLog(@NotNull Server server, @NotNull String description, @Nullable String level, @Nullable String source) {
        SystemLog logEntry = SystemLog.builder()
            .description(description)
            .level(level != null ? level : "info")
            .source(source != null ? source : "system")
            .created(new Date())
            .build();

        return serverLogRepository.saveEntity(server, logEntry);
    }
}
