package gg.modl.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.server.ServerService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.support.AbstractMongoIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the {@link gg.modl.backend.auth.controller.PanelAuthController} endpoints through
 * the real Spring Security chain ({@link gg.modl.backend.infrastructure.filter.V1SecurityConfig})
 * with a real Mongo backend. Verifies that the session-cookie auth path is wired up correctly
 * — missing/expired sessions reach the controller's 401 branch via the {@code permitAll}
 * matcher on {@code /v1/panel/auth/**}, and valid sessions resolve back to the authenticated
 * email so the controller can return user state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PanelAuthSessionIntegrationTest extends AbstractMongoIntegrationTest {

    private static final String TEST_HOST = "alpha.modl.test";
    private static final String TEST_ADMIN_EMAIL = "admin@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantMongoAccess tenantMongoAccess;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private AuthConfiguration authConfiguration;

    @Autowired
    private ServerService serverService;

    private MongoTemplate globalMongoTemplate;

    @BeforeEach
    void seedServer() {
        globalMongoTemplate = tenantMongoAccess.global();
        globalMongoTemplate.dropCollection(Server.class);
        Server server = new Server("Alpha", "alpha", "server_alpha", TEST_ADMIN_EMAIL, true, ServerPlan.FREE);
        server.setApiKey("alpha-api-key");
        globalMongoTemplate.save(server);
        // The ServerService caches getServerFromDomain lookups — bust the cache so the new
        // server document is visible to ServerHeaderFilter on the next request.
        serverService.evictAllServerCaches();
    }

    @Test
    void getMeWithoutSessionCookieReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/panel/auth/me")
                .header("Host", TEST_HOST))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Not authenticated"));
    }

    @Test
    void getMeWithUnknownSessionCookieReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/panel/auth/me")
                .header("Host", TEST_HOST)
                .cookie(new Cookie(authConfiguration.getSessionCookieName(), "session-that-does-not-exist")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getMeWithValidSessionReturnsAuthenticatedProfile() throws Exception {
        Server server = globalMongoTemplate.findAll(Server.class).iterator().next();
        AuthSessionData session = sessionService.createSession(server, TEST_ADMIN_EMAIL, "127.0.0.1", "integration-test");

        mockMvc.perform(get("/v1/panel/auth/me")
                .header("Host", TEST_HOST)
                .cookie(new Cookie(authConfiguration.getSessionCookieName(), session.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(TEST_ADMIN_EMAIL));

        AuthSessionData refreshed = sessionService.findAndRefreshSession(server, session.getId()).orElseThrow();
        assertThat(refreshed.getEmail()).isEqualTo(TEST_ADMIN_EMAIL);
        assertThat(refreshed.getExpiresAt()).isAfter(Date.from(Instant.now()));
    }

    @Test
    void sessionFromOneServerIsRejectedWhenPresentedAtAnotherServersHost() throws Exception {
        // Seed a second tenant with its own per-tenant session storage.
        Server alpha = globalMongoTemplate.findAll(Server.class).iterator().next();
        Server beta = new Server("Beta", "beta", "server_beta", "beta-admin@example.com", true, ServerPlan.FREE);
        beta.setApiKey("beta-api-key");
        globalMongoTemplate.save(beta);

        // Session is created against alpha; sessions live per-tenant via SessionService.createSession,
        // so beta's tenant DB never sees this row.
        AuthSessionData alphaSession = sessionService.createSession(
            alpha, TEST_ADMIN_EMAIL, "127.0.0.1", "integration-test");

        // Presenting alpha's session against beta's host must NOT authenticate as alpha's admin.
        mockMvc.perform(get("/v1/panel/auth/me")
                .header("Host", "beta.modl.test")
                .cookie(new Cookie(authConfiguration.getSessionCookieName(), alphaSession.getId())))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false));

        // The original session is untouched in alpha's tenant.
        assertThat(sessionService.findAndRefreshSession(alpha, alphaSession.getId())).isPresent();
    }
}
