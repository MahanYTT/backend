package gg.modl.backend.dashboard.controller.v3;

import static gg.modl.proto.modl.v1.MinecraftDashboardStatsResponse.newBuilder;

import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.proto.modl.v1.MinecraftDashboardResponse;
import lombok.experimental.UtilityClass;

@UtilityClass
final class MinecraftDashboardProtoMapper {

    static MinecraftDashboardResponse toMinecraftDashboardResponse(MinecraftDashboardStatsResponse stats) {
        return MinecraftDashboardResponse.newBuilder()
            .setStatus(200)
            .setStats(newBuilder()
                .setUnresolvedReports(stats.unresolvedReports())
                .setUnresolvedTickets(stats.unresolvedTickets())
                .setOnlineStaff(stats.onlineStaff())
                .setOnlinePlayers(stats.onlinePlayers())
                .setActiveBans(stats.activeBans())
                .setActiveMutes(stats.activeMutes())
                .setTotalActivePunishments(stats.totalActivePunishments())
                .setTotalPlayers(stats.totalPlayers())
                .build())
            .build();
    }
}
