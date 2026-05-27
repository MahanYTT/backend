package gg.modl.backend.auth.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.database.mongo.repository.AuthSessionMongoRepository;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Test
    void createSessionDoesNotDeleteSiblingPanelSessionsForEmail() {
        AuthSessionMongoRepository sessionRepository = mock(AuthSessionMongoRepository.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        SessionService sessionService = new SessionService(sessionRepository, new AuthConfiguration(), idGenerator);
        Server server = new Server("Alpha", "alpha", "server_alpha", "owner@example.com", true, ServerPlan.FREE);
        server.setId("server_alpha");

        when(idGenerator.generateToken()).thenReturn("panel-session-id");
        when(sessionRepository.saveForServer(eq(server), any(AuthSessionData.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));

        sessionService.createSession(server, "staff@example.com", "127.0.0.1", "JUnit");

        verify(sessionRepository, never()).deleteByEmail(server, "staff@example.com");
        verify(sessionRepository).saveForServer(eq(server), any(AuthSessionData.class));
    }

    @Test
    void createAdminSessionDoesNotDeleteSiblingAdminSessionsForEmail() {
        AuthSessionMongoRepository sessionRepository = mock(AuthSessionMongoRepository.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        SessionService sessionService = new SessionService(sessionRepository, new AuthConfiguration(), idGenerator);

        when(idGenerator.generateToken()).thenReturn("admin-session-id");
        when(sessionRepository.saveForGlobal(any(AuthSessionData.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        sessionService.createAdminSession("admin@example.com");

        verify(sessionRepository, never()).deleteByEmailGlobal("admin@example.com");
        verify(sessionRepository).saveForGlobal(any(AuthSessionData.class));
    }
}
