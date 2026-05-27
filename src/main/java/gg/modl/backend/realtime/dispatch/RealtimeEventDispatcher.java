package gg.modl.backend.realtime.dispatch;

import org.jetbrains.annotations.NotNull;

public interface RealtimeEventDispatcher {
    @NotNull RealtimeDispatchResult publish(@NotNull RealtimeOutboundEvent event);
}
