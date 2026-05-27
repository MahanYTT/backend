package gg.modl.backend.staff.controller.v3;

import gg.modl.backend.staff.dto.response.MinecraftStaffPermissionsResponse;
import gg.modl.backend.staff.dto.response.MinecraftStaffSummaryResponse;
import gg.modl.proto.modl.v1.StaffListResponse;
import gg.modl.proto.modl.v1.StaffPermissionsListResponse;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import lombok.experimental.UtilityClass;

@UtilityClass
final class MinecraftStaffProtoMapper {

    static StaffListResponse toStaffListResponse(
        List<MinecraftStaffSummaryResponse> staffList
    ) {
        StaffListResponse.Builder response = StaffListResponse.newBuilder()
            .setStatus(200);

        if (staffList != null) {
            staffList.forEach(staff -> addMinecraftStaffSummaryResponse(response, staff));
        }

        return response.build();
    }

    static StaffPermissionsListResponse toStaffPermissionsListResponse(
        List<MinecraftStaffPermissionsResponse> staffList
    ) {
        StaffPermissionsListResponse.StaffPermissionsData.Builder data =
            StaffPermissionsListResponse.StaffPermissionsData.newBuilder();

        if (staffList != null) {
            staffList.forEach(staff -> addMinecraftStaffPermissionsResponse(data, staff));
        }

        return StaffPermissionsListResponse.newBuilder()
            .setStatus(200)
            .setData(data)
            .build();
    }

    private static void addMinecraftStaffSummaryResponse(
        StaffListResponse.Builder response,
        MinecraftStaffSummaryResponse staff
    ) {
        int index = response.getStaffCount();
        response.addStaffBuilder()
            .setLastSeen(toEpochMillis(staff.lastSeen()))
            .setTotalPlaytimeMs(staff.totalPlaytimeMs())
            .setPunishmentsIssuedCount(staff.punishmentsIssuedCount())
            .setCreatedAt(toEpochMillis(staff.createdAt()))
            .setUpdatedAt(toEpochMillis(staff.updatedAt()));

        setIfNotNull(value -> response.getStaffBuilder(index).setId(value), staff.id());
        setIfNotNull(value -> response.getStaffBuilder(index).setUsername(value), staff.username());
        setIfNotNull(value -> response.getStaffBuilder(index).setEmail(value), staff.email());
        setIfNotNull(value -> response.getStaffBuilder(index).setRole(value), staff.role());
        setIfNotNull(value -> response.getStaffBuilder(index).setMinecraftUuid(value), staff.minecraftUuid());
        setIfNotNull(value -> response.getStaffBuilder(index).setMinecraftUsername(value), staff.minecraftUsername());
        setIfNotNull(value -> response.getStaffBuilder(index).setLastServer(value), staff.lastServer());
        addAllIfNotNull(value -> response.getStaffBuilder(index).addAllPermissions(value), staff.permissions());
    }

    private static void addMinecraftStaffPermissionsResponse(
        StaffPermissionsListResponse.StaffPermissionsData.Builder data,
        MinecraftStaffPermissionsResponse staff
    ) {
        int index = data.getStaffCount();
        data.addStaffBuilder();

        setIfNotNull(value -> data.getStaffBuilder(index).setMinecraftUuid(value), staff.minecraftUuid());
        setIfNotNull(value -> data.getStaffBuilder(index).setMinecraftUsername(value), staff.minecraftUsername());
        setIfNotNull(value -> data.getStaffBuilder(index).setStaffUsername(value), staff.staffUsername());
        setIfNotNull(value -> data.getStaffBuilder(index).setStaffId(value), staff.staffId());
        setIfNotNull(value -> data.getStaffBuilder(index).setStaffRole(value), staff.staffRole());
        addAllIfNotNull(value -> data.getStaffBuilder(index).addAllPermissions(value), staff.permissions());
        setIfNotNull(value -> data.getStaffBuilder(index).setEmail(value), staff.email());
    }

    private static long toEpochMillis(Date date) {
        return date != null ? date.getTime() : 0L;
    }

    private static void setIfNotNull(Consumer<String> setter, String value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static void addAllIfNotNull(
        Consumer<Iterable<String>> setter,
        List<String> values
    ) {
        if (values != null) {
            setter.accept(values);
        }
    }
}
