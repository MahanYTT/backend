package gg.modl.backend.player.service;

import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffRoleMongoRepository;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.proto.modl.v1.SyncActiveStaffMember;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SyncActiveStaffService {
    private final StaffMongoRepository staffRepository;
    private final StaffRoleMongoRepository staffRoleRepository;

    public List<SyncActiveStaffMember> getActiveStaffMembers(Server server, Map<String, String> onlinePlayerIps) {
        List<Staff> staffWithMinecraft = staffRepository.findAssignedMinecraftStaff(server);
        Map<String, List<String>> permissionsByRole = loadPermissionsByRole(server, staffWithMinecraft);

        List<SyncActiveStaffMember> result = new ArrayList<>();
        for (Staff staff : staffWithMinecraft) {
            List<String> permissions = permissionsByRole.getOrDefault(staff.getRole(), List.of());

            String currentIp = onlinePlayerIps.get(staff.getAssignedMinecraftUuid());
            boolean sessionValid = staff.getTwoFactorSessionExpiresAt() != null
                                   && staff.getTwoFactorSessionExpiresAt() > Instant.now().toEpochMilli()
                                   && staff.getTwoFactorSessionIp() != null
                                   && staff.getTwoFactorSessionIp().equals(currentIp);

            SyncActiveStaffMember.Builder builder = SyncActiveStaffMember.newBuilder()
                .setMinecraftUuid(staff.getAssignedMinecraftUuid() != null ? staff.getAssignedMinecraftUuid() : "")
                .setMinecraftUsername(staff.getAssignedMinecraftUsername() != null ? staff.getAssignedMinecraftUsername() : "")
                .setStaffUsername(staff.getUsername() != null ? staff.getUsername() : "")
                .setStaffId(staff.getId() != null ? staff.getId() : "")
                .setStaffRole(staff.getRole() != null ? staff.getRole() : "")
                .setEmail(staff.getEmail() != null ? staff.getEmail() : "")
                .setTwoFactorSessionValid(sessionValid)
                .addAllPermissions(permissions);
            result.add(builder.build());
        }

        return result;
    }

    private Map<String, List<String>> loadPermissionsByRole(Server server, List<Staff> staffMembers) {
        Set<String> roleNames = new HashSet<>();
        for (Staff staff : staffMembers) {
            if (staff.getRole() != null && !staff.getRole().isBlank()) {
                roleNames.add(staff.getRole());
            }
        }
        if (roleNames.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new HashMap<>();
        for (StaffRole role : staffRoleRepository.findByNames(server, roleNames)) {
            result.put(role.getName(), role.getPermissions() != null ? role.getPermissions() : List.of());
        }
        return result;
    }
}
