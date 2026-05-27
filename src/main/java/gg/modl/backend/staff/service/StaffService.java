package gg.modl.backend.staff.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.modl.backend.database.mongo.repository.InvitationMongoRepository;
import gg.modl.backend.database.mongo.repository.PlayerMongoRepository;
import gg.modl.backend.database.mongo.repository.PunishmentMongoRepository;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.database.mongo.repository.StaffRoleMongoRepository;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.util.UuidUtil;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.service.PlayerDataUtils;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.staff.data.Invitation;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.request.AssignMinecraftPlayerRequest;
import gg.modl.backend.staff.dto.request.CreateStaffRequest;
import gg.modl.backend.staff.dto.request.UpdateStaffRequest;
import gg.modl.backend.staff.dto.response.AvailablePlayerResponse;
import gg.modl.backend.staff.dto.response.MinecraftStaffPermissionsResponse;
import gg.modl.backend.staff.dto.response.MinecraftStaffSummaryResponse;
import gg.modl.backend.staff.dto.response.StaffResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StaffService {
    private final InvitationMongoRepository invitationRepository;
    private final StaffMongoRepository staffRepository;
    private final StaffRoleMongoRepository staffRoleRepository;
    private final PlayerMongoRepository playerRepository;
    private final PunishmentMongoRepository punishmentRepository;
    private final ServerMongoRepository serverRepository;
    private final PlayerService playerService;
    private final PermissionService permissionService;
    private final ServerTimestampService serverTimestampService;

    private final Cache<String, Optional<Staff>> staffByEmailCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(2))
        .build();

    public List<StaffResponse> getAllStaff(Server server) {
        List<Staff> staffMembers = staffRepository.findAll(server);
        List<Invitation> pendingInvitations = invitationRepository.findActiveInvitations(server, new Date());

        List<StaffResponse> result = new ArrayList<>();

        // Check if the Super Admin already has a staff record
        String adminEmail = server.getAdminEmail();
        boolean superAdminFound = false;

        for (Staff staff : staffMembers) {
            result.add(toStaffResponse(staff, "Active"));
            if (adminEmail != null && adminEmail.equalsIgnoreCase(staff.getEmail())) {
                superAdminFound = true;
            }
        }

        // Expose the owner in listings without creating a privileged staff document implicitly.
        if (!superAdminFound && adminEmail != null) {
            Staff superAdmin = Staff.builder()
                .email(adminEmail)
                .username("Admin")
                .role("Super Admin")
                .createdAt(server.getCreatedAt())
                .updatedAt(new Date())
                .build();
            result.add(0, toStaffResponse(superAdmin, "Active"));
        }

        for (Invitation invitation : pendingInvitations) {
            result.add(new StaffResponse(
                invitation.getId(),
                invitation.getEmail(),
                null,
                invitation.getRole(),
                "Pending Invitation",
                null,
                null,
                invitation.getCreatedAt()
            ));
        }

        return result;
    }

    private StaffResponse toStaffResponse(Staff staff, String status) {
        return new StaffResponse(
            staff.getId(),
            staff.getEmail(),
            staff.getUsername(),
            staff.getRole(),
            status,
            staff.getAssignedMinecraftUuid(),
            staff.getAssignedMinecraftUsername(),
            staff.getCreatedAt()
        );
    }

    public Optional<StaffResponse> getStaffByUsername(Server server, String username) {
        return staffRepository.findByUsername(server, username).map(s -> toStaffResponse(s, "Active"));
    }

    public boolean checkUsernameExists(Server server, String username) {
        return staffRepository.existsByUsername(server, username);
    }

    public StaffResponse createStaff(Server server, CreateStaffRequest request, String performerEmail, String performerRole) {
        if (staffRepository.existsByEmailOrUsername(server, request.email(), request.username())) {
            throw new ConflictException("Staff member with this email or username already exists");
        }

        String role = request.role() != null ? request.role() : "Helper";
        role = validateGrantableRole(server, role, performerRole).getName();

        Staff staff = Staff.builder()
            .email(request.email())
            .username(request.username())
            .role(role)
            .createdAt(new Date())
            .updatedAt(new Date())
            .build();

        staffRepository.saveEntity(server, staff);
        evictStaffByEmailCache(server, staff.getEmail());

        return toStaffResponse(staff, "Active");
    }

    public Optional<StaffResponse> updateStaff(Server server, String username, UpdateStaffRequest request, String currentUserEmail) {
        Staff staff = staffRepository.findByUsername(server, username).orElse(null);

        if (staff == null) {
            return Optional.empty();
        }

        boolean hasChanges = false;

        if (request.email() != null && !request.email().equals(staff.getEmail())) {
            if (!staff.getEmail().equalsIgnoreCase(currentUserEmail)) {
                throw new ForbiddenException("You can only change your own email address");
            }

            if (staffRepository.existsByEmailExact(server, request.email())) {
                throw new ConflictException("Email address already in use");
            }

            staff.setEmail(request.email());
            hasChanges = true;
        }

        if (hasChanges) {
            staff.setUpdatedAt(new Date());
            evictStaffByEmailCache(server, currentUserEmail);
            if (request.email() != null) {
                evictStaffByEmailCache(server, request.email());
            }
            staff = staffRepository.saveEntity(server, staff);
        }

        return Optional.ofNullable(staff).map(s -> toStaffResponse(s, "Active"));
    }

    public boolean deleteStaff(Server server, String id, String removerEmail, String removerRole) {
        if (invitationRepository.deleteById(server, id)) {
            return true;
        }

        Staff staffToRemove = staffRepository.findById(server, id).orElse(null);

        if (staffToRemove == null) {
            return false;
        }

        if (staffToRemove.getEmail().equalsIgnoreCase(removerEmail)) {
            throw new ValidationException("You cannot remove yourself");
        }

        if (server.getAdminEmail() != null &&
            staffToRemove.getEmail().equalsIgnoreCase(server.getAdminEmail())) {
            throw new ForbiddenException("Cannot remove the server administrator");
        }

        staffRepository.deleteById(server, id);
        evictStaffByEmailCache(server, staffToRemove.getEmail());
        serverTimestampService.updateStaffPermissionsTimestamp(server);
        return true;
    }

    public Optional<StaffResponse> updateStaffRole(Server server, String id, String newRole, String performerEmail, String performerRole) {
        Staff staff = staffRepository.findById(server, id).orElse(null);

        if (staff == null) {
            return Optional.empty();
        }

        if (server.getAdminEmail() != null &&
            staff.getEmail().equalsIgnoreCase(server.getAdminEmail())) {
            throw new ForbiddenException("Cannot change the role of the server administrator");
        }

        if (staff.getEmail().equalsIgnoreCase(performerEmail)) {
            throw new ForbiddenException("You cannot change your own role");
        }

        StaffRole validatedRole = validateGrantableRole(server, newRole, performerRole);
        staff.setRole(validatedRole.getName());
        staff.setUpdatedAt(new Date());
        Staff saved = staffRepository.saveEntity(server, staff);
        evictStaffByEmailCache(server, staff.getEmail());
        serverTimestampService.updateStaffPermissionsTimestamp(server);

        return Optional.of(toStaffResponse(saved, "Active"));
    }

    private StaffRole validateGrantableRole(Server server, String targetRoleName, String performerRoleName) {
        StaffRole targetRole = permissionService.getRoleByName(server, targetRoleName)
            .orElseThrow(() -> new ValidationException("Unknown staff role"));
        if ("super-admin".equals(targetRole.getId()) || "Super Admin".equals(targetRole.getName())) {
            throw new ForbiddenException("You do not have authority to grant this role");
        }
        if (performerRoleName == null || performerRoleName.isBlank()) {
            throw new ForbiddenException("You do not have authority to grant staff roles");
        }
        if ("Super Admin".equals(performerRoleName)) {
            return targetRole;
        }
        StaffRole performerRole = permissionService.getRoleByName(server, performerRoleName)
            .orElseThrow(() -> new ForbiddenException("You do not have authority to grant staff roles"));
        if (performerRole.getOrder() >= targetRole.getOrder()) {
            throw new ForbiddenException("You do not have authority to grant this role");
        }
        return targetRole;
    }

    public List<MinecraftStaffSummaryResponse> getMinecraftStaffSummary(Server server) {
        List<Staff> allStaff = staffRepository.findAll(server);

        PlayerStaffData playerStaffData = loadPlayerStaffData(server, allStaff);
        Map<String, Integer> punishmentCounts = loadPunishmentCounts(server);
        Map<String, List<String>> permissionsByRole = loadPermissionsByRole(server, allStaff);

        return allStaff.stream()
            .map(staff -> {
                int punishmentsIssuedCount = 0;
                if (staff.getAssignedMinecraftUsername() != null && punishmentCounts.containsKey(staff.getAssignedMinecraftUsername())) {
                    punishmentsIssuedCount = punishmentCounts.get(staff.getAssignedMinecraftUsername());
                } else if (staff.getUsername() != null && punishmentCounts.containsKey(staff.getUsername())) {
                    punishmentsIssuedCount = punishmentCounts.get(staff.getUsername());
                }

                return new MinecraftStaffSummaryResponse(
                    staff.getId(),
                    staff.getUsername(),
                    staff.getEmail(),
                    staff.getRole(),
                    staff.getAssignedMinecraftUuid(),
                    staff.getAssignedMinecraftUsername(),
                    permissionsByRole.getOrDefault(staff.getRole(), List.of()),
                    staff.getLastSeen(),
                    playerStaffData.playtimeMap().getOrDefault(staff.getAssignedMinecraftUuid(), 0L),
                    playerStaffData.lastServerMap().get(staff.getAssignedMinecraftUuid()),
                    punishmentsIssuedCount,
                    staff.getCreatedAt(),
                    staff.getUpdatedAt()
                );
            })
            .toList();
    }

    private record PlayerStaffData(Map<String, Long> playtimeMap, Map<String, String> lastServerMap) {}

    private PlayerStaffData loadPlayerStaffData(Server server, List<Staff> allStaff) {
        List<String> assignedUuids = allStaff.stream()
            .map(Staff::getAssignedMinecraftUuid)
            .filter(uuid -> uuid != null && !uuid.isBlank())
            .distinct()
            .toList();

        if (assignedUuids.isEmpty()) {
            return new PlayerStaffData(Map.of(), Map.of());
        }

        Map<String, Long> playtimeMap = new HashMap<>();
        Map<String, String> lastServerMap = new HashMap<>();

        for (Player player : playerRepository.findByMinecraftUuids(server, assignedUuids)) {
            if (player.getMinecraftUuid() == null || player.getData() == null) {
                continue;
            }

            String uuid = player.getMinecraftUuid().toString();

            Object playtimeObj = player.getData().get("totalPlaytimeSeconds");
            if (playtimeObj instanceof Number playtimeSeconds) {
                playtimeMap.put(uuid, playtimeSeconds.longValue() * 1000L);
            }

            Object lastServerObj = player.getData().get("lastServer");
            if (lastServerObj instanceof String lastServer) {
                lastServerMap.put(uuid, lastServer);
            }
        }

        return new PlayerStaffData(playtimeMap, lastServerMap);
    }

    private Map<String, Integer> loadPunishmentCounts(Server server) {
        try {
            return punishmentRepository.countPunishmentsByIssuerName(server);
        } catch (Exception e) {
            log.warn("Failed to load punishment counts for server {}", server.getDatabaseName(), e);
            return Map.of();
        }
    }

    private Map<String, List<String>> loadPermissionsByRole(Server server, List<Staff> staffMembers) {
        Set<String> roleNames = staffMembers.stream()
            .map(Staff::getRole)
            .filter(role -> role != null && !role.isBlank())
            .collect(Collectors.toSet());
        if (roleNames.isEmpty()) {
            return Map.of();
        }

        return staffRoleRepository.findByNames(server, roleNames)
            .stream()
            .collect(Collectors.toMap(
                StaffRole::getName,
                role -> role.getPermissions() != null ? role.getPermissions() : List.of(),
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    public List<MinecraftStaffPermissionsResponse> getMinecraftStaffPermissions(Server server) {
        List<Staff> staffWithMinecraft = staffRepository.findAssignedMinecraftStaff(server);
        Map<String, List<String>> permissionsByRole = loadPermissionsByRole(server, staffWithMinecraft);

        return staffWithMinecraft.stream()
            .map(staff -> new MinecraftStaffPermissionsResponse(
                staff.getAssignedMinecraftUuid(),
                staff.getAssignedMinecraftUsername() != null ? staff.getAssignedMinecraftUsername() : "",
                staff.getUsername() != null ? staff.getUsername() : "",
                staff.getId(),
                staff.getRole() != null ? staff.getRole() : "",
                permissionsByRole.getOrDefault(staff.getRole(), List.of()),
                staff.getEmail() != null ? staff.getEmail() : ""
            ))
            .toList();
    }

    public boolean updateMinecraftStaffRole(Server server, String id, String roleName) {
        Staff staff = staffRepository.findById(server, id).orElse(null);
        if (staff == null) {
            return false;
        }

        if (!staffRoleRepository.existsByName(server, roleName)) {
            throw new ResourceNotFoundException("Role not found");
        }

        staff.setRole(roleName);
        staff.setUpdatedAt(new Date());
        staffRepository.saveEntity(server, staff);
        evictStaffByEmailCache(server, staff.getEmail());
        serverTimestampService.updateStaffPermissionsTimestamp(server);
        return true;
    }

    public boolean markStaffDisconnected(Server server, String minecraftUuid) {
        return staffRepository.updateLastSeenByAssignedMinecraftUuid(server, UuidUtil.normalizeUuid(minecraftUuid));
    }

    public Optional<StaffResponse> assignMinecraftPlayer(Server server, String username, AssignMinecraftPlayerRequest request) {
        Staff staff = staffRepository.findByUsername(server, username).orElse(null);

        if (staff == null) {
            return Optional.empty();
        }

        if ((request.minecraftUuid() == null || request.minecraftUuid().isEmpty()) &&
            (request.minecraftUsername() == null || request.minecraftUsername().isEmpty())) {
            staff.setAssignedMinecraftUuid(null);
            staff.setAssignedMinecraftUsername(null);
            staff.setUpdatedAt(new Date());
            staffRepository.saveEntity(server, staff);
            evictStaffByEmailCache(server, staff.getEmail());
            serverTimestampService.updateStaffPermissionsTimestamp(server);
            return Optional.of(toStaffResponse(staff, "Active"));
        }

        Player player = request.minecraftUuid() != null && !request.minecraftUuid().isEmpty()
                        ? playerRepository.findByMinecraftUuid(server, UuidUtil.normalizeUuid(request.minecraftUuid())).orElse(null)
                        : playerService.findBestByUsername(server, request.minecraftUsername()).orElse(null);
        if (player == null) {
            throw new ResourceNotFoundException("Minecraft player not found");
        }

        Staff existingAssignment = staffRepository
            .findByAssignedMinecraftUuidExcludingId(server, player.getMinecraftUuid().toString(), staff.getId())
            .orElse(null);
        if (existingAssignment != null) {
            throw new ConflictException("This Minecraft player is already assigned to " + existingAssignment.getUsername());
        }

        String currentUsername = PlayerDataUtils.extractLatestUsername(player.getUsernames());

        staff.setAssignedMinecraftUuid(player.getMinecraftUuid().toString());
        staff.setAssignedMinecraftUsername(currentUsername);
        staff.setUpdatedAt(new Date());
        staffRepository.saveEntity(server, staff);
        evictStaffByEmailCache(server, staff.getEmail());
        serverTimestampService.updateStaffPermissionsTimestamp(server);
        return Optional.of(toStaffResponse(staff, "Active"));
    }

    public List<AvailablePlayerResponse> getAvailablePlayers(Server server) {
        List<Staff> staffWithPlayers = staffRepository.findAssignedMinecraftStaff(server);

        List<String> assignedUuids = staffWithPlayers.stream()
            .map(Staff::getAssignedMinecraftUuid)
            .filter(uuid -> uuid != null && !uuid.isEmpty())
            .toList();

        List<Player> players = playerRepository.findAvailablePlayers(server, assignedUuids, 100);

        return players.stream()
            .map(player -> new AvailablePlayerResponse(
                player.getMinecraftUuid().toString(),
                PlayerDataUtils.extractLatestUsername(player.getUsernames())
            ))
            .toList();
    }

    public Optional<Staff> updateOrCreateProfileUsername(Server server, String email, String newUsername,
                                                         boolean createIfNotExists, String newLanguage, String newDateFormat) {
        Staff staff = staffRepository.findByEmailIgnoreCase(server, email).orElse(null);

        if (staff == null) {
            return Optional.empty();
        }

        boolean hasChanges = false;
        if (newUsername != null && !newUsername.equals(staff.getUsername())) {
            if (staffRepository.existsByUsernameExcludingId(server, newUsername, staff.getId())) {
                throw new ConflictException("Username already in use");
            }
            staff.setUsername(newUsername);
            hasChanges = true;
        }

        if (newLanguage != null && List.of("en", "de", "es").contains(newLanguage)) {
            staff.setLanguage(newLanguage);
            hasChanges = true;
        }

        if (newDateFormat != null && List.of("MM/DD/YYYY", "DD/MM/YYYY", "YYYY-MM-DD").contains(newDateFormat)) {
            staff.setDateFormat(newDateFormat);
            hasChanges = true;
        }

        if (hasChanges) {
            staff.setUpdatedAt(new Date());
            staff = staffRepository.saveEntity(server, staff);
            evictStaffByEmailCache(server, email);
        }

        return Optional.of(staff);
    }

    public Optional<Staff> updateEmail(Server server, String currentEmail, String newEmail, boolean isSuperAdmin) {
        if (staffRepository.existsByEmailIgnoreCaseExcluding(server, newEmail, currentEmail)) {
            throw new ConflictException("Email address already in use");
        }

        if (isSuperAdmin && serverRepository.existsByAdminEmailExcludingId(newEmail, server.getId())) {
            throw new ConflictException("Email address already in use");
        }

        Staff staff = staffRepository.findByEmailIgnoreCase(server, currentEmail).orElse(null);

        if (staff == null) {
            if (!isSuperAdmin) {
                return Optional.empty();
            }
            staff = Staff.builder()
                .email(newEmail)
                .username("Admin")
                .role("Super Admin")
                .createdAt(new Date())
                .updatedAt(new Date())
                .build();
            staff = staffRepository.saveEntity(server, staff);
        } else {
            staff.setEmail(newEmail);
            staff.setUpdatedAt(new Date());
            staff = staffRepository.saveEntity(server, staff);
        }

        evictStaffByEmailCache(server, currentEmail);
        evictStaffByEmailCache(server, newEmail);

        if (isSuperAdmin) {
            serverRepository.updateAdminEmail(server.getId(), newEmail);
        }

        return Optional.of(staff);
    }

    public Optional<Staff> getStaffByEmail(Server server, String email) {
        String normalized = EmailAddressUtil.normalize(email);
        if (normalized == null) {
            return Optional.empty();
        }
        String cacheKey = server.getId() + ":" + normalized;
        return staffByEmailCache.get(cacheKey, key ->
            staffRepository.findByEmailIgnoreCase(server, email)
        );
    }

    public void evictStaffByEmailCache(Server server, String email) {
        String normalized = EmailAddressUtil.normalize(email);
        if (normalized == null) {
            return;
        }
        staffByEmailCache.invalidate(server.getId() + ":" + normalized);
    }

    public void evictAllStaffCaches() {
        staffByEmailCache.invalidateAll();
    }

}
