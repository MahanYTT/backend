package gg.modl.backend.realtime.transport;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.proto.modl.v1.Error;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.Heartbeat;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ReconnectAction;
import gg.modl.proto.modl.v1.ReconnectAdvice;
import gg.modl.proto.modl.v1.ReconnectReason;
import gg.modl.proto.modl.v1.ServerHello;
import gg.modl.proto.modl.v1.Topic;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;

@Component
@RequiredArgsConstructor
public class RealtimeCodec {
    private final RealtimeProperties properties;

    public RealtimeEnvelope parse(BinaryMessage message) throws InvalidProtocolBufferException {
        int size = message.getPayloadLength();
        if (size > properties.getMaxFrameBytes()) {
            throw new OversizedRealtimeFrameException("Realtime frame exceeds max size");
        }

        byte[] payload = new byte[size];
        message.getPayload().get(payload);
        return RealtimeEnvelope.parseFrom(payload);
    }

    public BinaryMessage serverHello(String connectionId, Collection<Topic> acceptedTopics) {
        ServerHello hello = ServerHello.newBuilder()
            .setProtocolVersion(properties.getProtocolVersion())
            .setConnectionId(connectionId)
            .addAllAcceptedTopics(acceptedTopics)
            .setServerTime(Instant.now().toString())
            .build();

        return toMessage(baseEnvelope().setServerHello(hello).build());
    }

    public BinaryMessage heartbeat(long sequence) {
        Heartbeat heartbeat = Heartbeat.newBuilder()
            .setSequence(sequence)
            .build();
        return toMessage(baseEnvelope().setHeartbeat(heartbeat).build());
    }

    public BinaryMessage error(ErrorCode code, String message) {
        Error error = Error.newBuilder()
            .setCode(code)
            .setMessage(message)
            .build();
        return toMessage(baseEnvelope().setError(error).build());
    }

    public BinaryMessage deployDrainAdvice() {
        ReconnectAdvice advice = ReconnectAdvice.newBuilder()
            .setReason(ReconnectReason.RECONNECT_REASON_DEPLOYMENT)
            .setAction(ReconnectAction.RECONNECT_ACTION_RECONNECT)
            .setRetryAfterMs(properties.getDeployDrainRetryAfterMs())
            .setMessage("Realtime backend is draining for deployment; reconnect after the retry delay")
            .build();
        return toMessage(baseEnvelope().setReconnectAdvice(advice).build());
    }

    public RealtimeEnvelope buildOutboundEnvelope(RealtimeEnvelope envelope) {
        RealtimeEnvelope.Builder builder = envelope.toBuilder();
        if (builder.getEventId().isBlank()) {
            builder.setEventId(UUID.randomUUID().toString());
        }
        if (builder.getProtocolVersion() == 0) {
            builder.setProtocolVersion(properties.getProtocolVersion());
        }
        if (!builder.hasTimestamp()) {
            Instant now = Instant.now();
            builder.setTimestamp(Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build());
        }
        return builder.build();
    }

    public BinaryMessage outbound(RealtimeEnvelope envelope) {
        return toMessage(buildOutboundEnvelope(envelope));
    }

    public byte[] outboundPayload(RealtimeEnvelope envelope) {
        RealtimeEnvelope outboundEnvelope = buildOutboundEnvelope(envelope);
        byte[] payload = outboundEnvelope.toByteArray();
        if (payload.length > properties.getMaxFrameBytes()) {
            throw new OversizedRealtimeFrameException("Realtime outbound frame exceeds max size");
        }
        return payload;
    }

    private RealtimeEnvelope.Builder baseEnvelope() {
        Instant now = Instant.now();
        return RealtimeEnvelope.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setProtocolVersion(properties.getProtocolVersion())
            .setTimestamp(Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build());
    }

    private BinaryMessage toMessage(RealtimeEnvelope envelope) {
        byte[] payload = envelope.toByteArray();
        if (payload.length > properties.getMaxFrameBytes()) {
            throw new OversizedRealtimeFrameException("Realtime outbound frame exceeds max size");
        }
        return new BinaryMessage(payload);
    }
}
