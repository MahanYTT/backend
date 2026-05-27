package gg.modl.backend.realtime.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.proto.modl.v1.Topic;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealtimeTopicAuthorizerTest {

    @Test
    void panelTicketTopicsRequireReadPermission() {
        PermissionService permissionService = mock(PermissionService.class);
        StaffService staffService = mock(StaffService.class);
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(permissionService, staffService);
        Server server = server();

        Staff staff = Staff.builder()
            .email("staff@example.com")
            .role("Support")
            .build();
        when(staffService.getStaffByEmail(server, "staff@example.com")).thenReturn(Optional.of(staff));
        when(permissionService.hasPermission(server, "Support", "ticket.view.all")).thenReturn(true);

        assertTrue(authorizer.canSubscribe(
            RealtimePrincipal.panel(server, "staff@example.com"),
            Topic.TOPIC_PANEL_TICKETS
        ));
    }

    @Test
    void panelCannotSubscribeToMinecraftTopics() {
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(mock(PermissionService.class), mock(StaffService.class));

        assertFalse(authorizer.canSubscribe(
            RealtimePrincipal.panel(server(), "staff@example.com"),
            Topic.TOPIC_MINECRAFT_PRESENCE
        ));
    }

    @Test
    void minecraftCanSubscribeOnlyToRuntimeMinecraftTopics() {
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(mock(PermissionService.class), mock(StaffService.class));
        RealtimePrincipal principal = RealtimePrincipal.minecraft(server(), "instance-1");

        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PERMISSIONS));
        assertTrue(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PRESENCE));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_TICKETS));
    }

    @Test
    void minecraftPresenceIsNotAcceptedUntilRuntimeSupportExists() {
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(mock(PermissionService.class), mock(StaffService.class));

        assertFalse(authorizer.canSubscribe(RealtimePrincipal.minecraft(server()), Topic.TOPIC_MINECRAFT_PRESENCE));
        assertFalse(authorizer.canSubscribe(RealtimePrincipal.minecraft(server(), " "), Topic.TOPIC_MINECRAFT_PRESENCE));
        assertFalse(authorizer.canSubscribe(RealtimePrincipal.minecraft(server(), "instance-1"), Topic.TOPIC_MINECRAFT_PRESENCE));
    }

    @Test
    void minecraftCannotSubscribeToCriticalTopics() {
        RealtimeTopicAuthorizer authorizer = new RealtimeTopicAuthorizer(mock(PermissionService.class), mock(StaffService.class));
        RealtimePrincipal principal = RealtimePrincipal.minecraft(server(), "instance-1");

        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_STAFF_NOTIFICATIONS));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PUNISHMENTS));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_PLAYER_NOTIFICATIONS));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_STAFF_2FA));
        assertFalse(authorizer.canSubscribe(principal, Topic.TOPIC_MINECRAFT_MIGRATION_TASKS));
    }

    private Server server() {
        Server server = new Server("server", "server", "server_db", "admin@example.com", true, ServerPlan.FREE);
        server.setId("server-id");
        return server;
    }
}
