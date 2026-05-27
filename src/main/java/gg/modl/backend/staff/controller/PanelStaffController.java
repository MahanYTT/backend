package gg.modl.backend.staff.controller;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.request.AssignMinecraftPlayerRequest;
import gg.modl.backend.staff.dto.request.CreateStaffRequest;
import gg.modl.backend.staff.dto.request.InviteStaffRequest;
import gg.modl.backend.staff.dto.request.UpdateStaffRequest;
import gg.modl.backend.staff.dto.request.UpdateStaffRoleRequest;
import gg.modl.backend.staff.dto.response.AvailablePlayerResponse;
import gg.modl.backend.staff.dto.response.InviteResultResponse;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.service.InvitationService;
import gg.modl.backend.staff.service.StaffService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_STAFF)
@RequiredArgsConstructor
public class PanelStaffController {
    private final StaffService staffService;
    private final InvitationService invitationService;

    @GetMapping
    public ResponseEntity<List<StaffResponse>> getAllStaff(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        List<StaffResponse> staff = staffService.getAllStaff(server);
        return ResponseEntity.ok(staff);
    }

    @GetMapping("/check-username/{username}")
    public ResponseEntity<Map<String, Boolean>> checkUsername(
        @PathVariable String username,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        boolean exists = staffService.checkUsernameExists(server, username);
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    @GetMapping("/{username}")
    public ResponseEntity<StaffResponse> getStaffByUsername(
        @PathVariable String username,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        return staffService.getStaffByUsername(server, username)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createStaff(
        @RequestBody @Valid CreateStaffRequest createRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String performerEmail = RequestUtil.getSessionEmail(request);
        String performerRole = resolvePerformerRole(server, performerEmail);

        StaffResponse staff = staffService.createStaff(server, createRequest, performerEmail, performerRole);
        return ResponseEntity.status(HttpStatus.CREATED).body(staff);
    }

    @PatchMapping("/{username}")
    public ResponseEntity<?> updateStaff(
        @PathVariable String username,
        @RequestBody @Valid UpdateStaffRequest updateRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String currentUserEmail = RequestUtil.getSessionEmail(request);

        return staffService.updateStaff(server, username, updateRequest, currentUserEmail)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<?> updateStaffRole(
        @PathVariable String id,
        @RequestBody @Valid UpdateStaffRoleRequest roleRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String performerEmail = RequestUtil.getSessionEmail(request);
        String performerRole = resolvePerformerRole(server, performerEmail);

        return staffService.updateStaffRole(server, id, roleRequest.role(), performerEmail, performerRole)
            .map(staff -> ResponseEntity.ok(Map.of(
                "message", "Role updated successfully.",
                "staffMember", staff
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteStaff(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String removerEmail = RequestUtil.getSessionEmail(request);
        String removerRole = staffService.getStaffByEmail(server, removerEmail)
            .map(Staff::getRole)
            .orElse("");

        boolean deleted = staffService.deleteStaff(server, id, removerEmail, removerRole);
        if (deleted) {
            return ResponseEntity.ok(Map.of("message", "Removed successfully."));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/invite")
    public ResponseEntity<?> inviteStaff(
        @RequestBody @Valid InviteStaffRequest inviteRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String inviterEmail = RequestUtil.getSessionEmail(request);
        String inviterRole = resolvePerformerRole(server, inviterEmail);

        InviteResultResponse result = invitationService.sendInvitations(server, inviteRequest, inviterEmail, inviterRole);

        if (result.success().isEmpty()) {
            return ResponseEntity.badRequest().body(result);
        } else if (result.failed().isEmpty()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", result.message()));
        } else {
            return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(result);
        }
    }

    @PostMapping("/invitations/{id}/resend")
    public ResponseEntity<?> resendInvitation(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        boolean resent = invitationService.resendInvitation(server, id);
        if (resent) {
            return ResponseEntity.ok(Map.of("message", "Invitation resent successfully"));
        }
        return ResponseEntity.notFound().build();
    }

    @PatchMapping("/{username}/minecraft-player")
    public ResponseEntity<?> assignMinecraftPlayer(
        @PathVariable String username,
        @RequestBody @Valid AssignMinecraftPlayerRequest assignRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);

        return staffService.assignMinecraftPlayer(server, username, assignRequest)
            .map(staff -> ResponseEntity.ok(Map.of(
                "message", (assignRequest.minecraftUuid() == null && assignRequest.minecraftUsername() == null)
                           ? "Minecraft player assignment cleared successfully"
                           : "Minecraft player assigned successfully",
                "staffMember", staff
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/available-players")
    public ResponseEntity<Map<String, List<AvailablePlayerResponse>>> getAvailablePlayers(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        List<AvailablePlayerResponse> players = staffService.getAvailablePlayers(server);
        return ResponseEntity.ok(Map.of("players", players));
    }

    private String resolvePerformerRole(Server server, String email) {
        if (email != null && server.getAdminEmail() != null && email.equalsIgnoreCase(server.getAdminEmail())) {
            return "Super Admin";
        }
        return staffService.getStaffByEmail(server, email)
            .map(Staff::getRole)
            .orElse("");
    }
}
