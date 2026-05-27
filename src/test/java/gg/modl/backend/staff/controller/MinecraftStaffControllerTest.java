package gg.modl.backend.staff.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.staff.controller.v1.MinecraftStaffController;
import gg.modl.backend.staff.service.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MinecraftStaffControllerTest {
    @Mock private StaffService staffService;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);

        mockMvc = MockMvcBuilders
            .standaloneSetup(new MinecraftStaffController(staffService))
            .defaultRequest(patch("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void updateStaffRoleUsesMinecraftSyncService() throws Exception {
        when(staffService.updateMinecraftStaffRole(server, "staff-1", "Moderator")).thenReturn(true);

        mockMvc.perform(patch(RESTMappingV1.MINECRAFT_STAFF + "/staff-1/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Moderator\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.success").value(true));

        verify(staffService).updateMinecraftStaffRole(server, "staff-1", "Moderator");
    }

    @Test
    void updateStaffRoleReturnsNotFoundForMissingStaff() throws Exception {
        when(staffService.updateMinecraftStaffRole(server, "missing", "Moderator")).thenReturn(false);

        mockMvc.perform(patch(RESTMappingV1.MINECRAFT_STAFF + "/missing/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Moderator\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }
}
