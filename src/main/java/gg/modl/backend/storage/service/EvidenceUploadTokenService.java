package gg.modl.backend.storage.service;

import gg.modl.backend.server.data.Server;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EvidenceUploadTokenService {

    private final ConcurrentHashMap<String, UploadToken> tokens = new ConcurrentHashMap<>();

    @NotNull
    public String createToken(@NotNull Server server, @NotNull String punishmentId, @NotNull String playerUuid, @NotNull String issuerName) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, new UploadToken(
            token,
            server.getDatabaseName(),
            punishmentId,
            playerUuid,
            issuerName,
            Instant.now()
        ));
        return token;
    }

    @Nullable
    public UploadToken validateToken(@NotNull String token) {
        UploadToken uploadToken = tokens.get(token);
        if (uploadToken == null) {
            return null;
        }
        if (uploadToken.isExpired()) {
            tokens.remove(token);
            return null;
        }
        return uploadToken;
    }

    public void invalidateToken(String token) {
        tokens.remove(token);
    }

    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void cleanupExpiredTokens() {
        tokens.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    public record UploadToken(
        String token,
        String serverDatabaseName,
        String punishmentId,
        String playerUuid,
        String issuerName,
        Instant createdAt
    ) {
        private static final long TTL_MINUTES = 30;

        public boolean isExpired() {
            return Instant.now().isAfter(createdAt.plusSeconds(TTL_MINUTES * 60));
        }
    }
}
