package gg.modl.backend.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.data.WebAuthnChallenge;
import gg.modl.backend.database.mongo.repository.WebAuthnChallengeMongoRepository;
import gg.modl.backend.database.mongo.repository.WebAuthnCredentialMongoRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebAuthnServiceTest {
    @Mock private WebAuthnChallengeMongoRepository challengeRepository;
    @Mock private WebAuthnCredentialMongoRepository credentialRepository;
    private WebAuthnService webAuthnService;

    @BeforeEach
    void setUp() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        webAuthnService = new WebAuthnService(challengeRepository, credentialRepository, authConfiguration);
        when(challengeRepository.saveEntity(any(Server.class), any(WebAuthnChallenge.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void registrationOptionsRequireUserVerification() {
        WebAuthnService.StartRegistrationResult result =
            webAuthnService.startRegistration(server(), "admin@example.com");

        assertTrue(result.optionsJson().contains("\"userVerification\":\"required\""));
    }

    @Test
    void discoverableAuthenticationOptionsRequireUserVerification() {
        WebAuthnService.StartAuthenticationResult result =
            webAuthnService.startDiscoverableAuthentication(server());

        assertTrue(result.optionsJson().contains("\"userVerification\":\"required\""));
    }

    private static Server server() {
        return new Server("server", "tenant", "db", "admin@example.com", true, ServerPlan.PREMIUM);
    }
}
