package gg.modl.backend.role.controller.v3;

import com.google.protobuf.Timestamp;
import gg.modl.backend.role.dto.response.RoleResponse;
import gg.modl.proto.modl.v1.MinecraftRole;
import gg.modl.proto.modl.v1.MinecraftRoleDetailResponse;
import gg.modl.proto.modl.v1.MinecraftRoleListResponse;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

final class MinecraftRolesProtoMapper {
    private MinecraftRolesProtoMapper() {
    }

    static MinecraftRoleListResponse toRoleListResponse(List<RoleResponse> roles) {
        MinecraftRoleListResponse.Builder response = MinecraftRoleListResponse.newBuilder()
            .setStatus(200);

        if (roles != null) {
            roles.stream()
                .map(MinecraftRolesProtoMapper::toMinecraftRole)
                .forEach(response::addRoles);
        }

        return response.build();
    }

    static MinecraftRoleDetailResponse toRoleDetailResponse(RoleResponse role) {
        return MinecraftRoleDetailResponse.newBuilder()
            .setStatus(200)
            .setRole(toMinecraftRole(role))
            .build();
    }

    private static MinecraftRole toMinecraftRole(RoleResponse role) {
        MinecraftRole.Builder builder = MinecraftRole.newBuilder()
            .setIsDefault(role.isDefault())
            .setOrder(role.order());

        setIfNotNull(builder::setId, role.id());
        setIfNotNull(builder::setName, role.name());
        setIfNotNull(builder::setDescription, role.description());
        addAllIfNotNull(builder::addAllPermissions, role.permissions());
        setTimestampIfNotNull(builder::setCreatedAt, role.createdAt());
        setTimestampIfNotNull(builder::setUpdatedAt, role.updatedAt());

        return builder.build();
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

    private static void setTimestampIfNotNull(
        Consumer<Timestamp> setter,
        Date date
    ) {
        if (date != null) {
            setter.accept(Timestamp.newBuilder()
                .setSeconds(date.getTime() / 1_000L)
                .setNanos((int) (date.getTime() % 1_000L) * 1_000_000)
                .build());
        }
    }
}
