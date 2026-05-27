package gg.modl.backend.staff.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.StaffMongoRepository;
import gg.modl.backend.infrastructure.config.ModlProperties;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.staff.data.Staff;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaffTwoFactorServiceTest {

    @Test
    void generateTokenLowercasesUuidBeforeQueryingRepository() {
        StaffMongoRepository staffRepository = mock(StaffMongoRepository.class);
        ModlProperties properties = mock(ModlProperties.class);
        when(properties.getDomain()).thenReturn("example.com");
        StaffTwoFactorService service = new StaffTwoFactorService(staffRepository, properties);
        Server server = server();
        when(staffRepository.createTwoFactorToken(eq(server), eq("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            anyString(), anyString(), anyLong())).thenReturn(true);

        service.generateToken(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "127.0.0.1");

        verify(staffRepository).createTwoFactorToken(eq(server), eq("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            anyString(), eq("127.0.0.1"), anyLong());
    }

    @Test
    void verifyTokenAllowsInitialPublicVerificationWithoutSessionEmail() {
        StaffMongoRepository staffRepository = mock(StaffMongoRepository.class);
        StaffTwoFactorService service = new StaffTwoFactorService(staffRepository, mock(ModlProperties.class));
        Server server = server();
        Staff staff = staff();
        when(staffRepository.findByTwoFactorToken(server, "token")).thenReturn(Optional.of(staff));
        when(staffRepository.activateTwoFactorSession(eq(server), eq("staff-1"), eq("token"), eq("127.0.0.1"), anyLong())).thenReturn(true);

        assertTrue(service.verifyToken(server, "token", null));
    }

    @Test
    void verifyTokenRejectsMismatchedSessionEmailWhenSessionExists() {
        StaffMongoRepository staffRepository = mock(StaffMongoRepository.class);
        StaffTwoFactorService service = new StaffTwoFactorService(staffRepository, mock(ModlProperties.class));
        Server server = server();
        when(staffRepository.findByTwoFactorToken(server, "token")).thenReturn(Optional.of(staff()));

        assertFalse(service.verifyToken(server, "token", "other@example.com"));
        verify(staffRepository, never()).activateTwoFactorSession(server, "staff-1", "token", "127.0.0.1", 0L);
    }

    @Test
    void verifyTokenRejectsExpiredToken() {
        StaffMongoRepository staffRepository = mock(StaffMongoRepository.class);
        StaffTwoFactorService service = new StaffTwoFactorService(staffRepository, mock(ModlProperties.class));
        Server server = server();
        Staff staff = staff();
        staff.setTwoFactorTokenCreatedAt(Instant.now().minusSeconds(601).toEpochMilli());
        when(staffRepository.findByTwoFactorToken(server, "token")).thenReturn(Optional.of(staff));

        assertFalse(service.verifyToken(server, "token", null));
        verify(staffRepository, never()).activateTwoFactorSession(server, "staff-1", "token", "127.0.0.1", 0L);
    }

    private static Server server() {
        return new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
    }

    private static Staff staff() {
        return Staff.builder()
            .id("staff-1")
            .email("staff@example.com")
            .twoFactorTokenCreatedAt(Instant.now().toEpochMilli())
            .twoFactorTokenIp("127.0.0.1")
            .build();
    }
}
