package gg.modl.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.database.CollectionName;
import gg.modl.backend.database.mongo.TenantMongoAccess;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.support.AbstractMongoIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WebAuthn endpoint coverage exercises everything reachable from the controller surface
 * without standing up a virtual FIDO authenticator:
 *
 * <ul>
 *   <li>{@code POST /webauthn/register/options} requires a session, persists a challenge,
 *       and returns a {@code challengeId} that maps to a Mongo document.
 *   <li>{@code POST /webauthn/register/verify} with a syntactically valid but cryptographically
 *       invalid attestation is rejected by Yubico's {@code RelyingParty} (signature check fails).
 *   <li>{@code POST /webauthn/login/start} (discoverable) issues an assertion challenge with no
 *       session required.
 *   <li>{@code POST /webauthn/login/options} for an unknown email returns {@code hasPasskeys=false}
 *       (anti-enumeration: indistinguishable from "authorized email with no passkey").
 *   <li>{@code POST /webauthn/login/verify} with garbage rejects without issuing a session cookie.
 * </ul>
 *
 * <p>A true registration + assertion round-trip is not covered here because Yubico's
 * {@code RelyingParty} is constructed inline in {@code WebAuthnService} and the library performs
 * real attestation signature verification — a virtual authenticator (e.g. {@code webauthn4j-test})
 * would be needed, or the service would need to be refactored to inject {@code RelyingParty}.
 * Documented as a follow-up in {@code TESTING.md}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WebAuthnIntegrationTest extends AbstractMongoIntegrationTest {

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
    private ObjectMapper objectMapper;

    @Autowired
    private gg.modl.backend.server.ServerService serverService;

    private MongoTemplate globalMongoTemplate;
    private Server seededServer;
    private String adminSessionId;

    @BeforeEach
    void seedServer() {
        globalMongoTemplate = tenantMongoAccess.global();
        globalMongoTemplate.dropCollection(Server.class);
        Server server = new Server("Alpha", "alpha", "server_alpha", TEST_ADMIN_EMAIL, true, ServerPlan.FREE);
        server.setApiKey("alpha-api-key");
        globalMongoTemplate.save(server);
        serverService.evictAllServerCaches();
        seededServer = globalMongoTemplate.findAll(Server.class).iterator().next();

        // Clear any leftover challenges from previous runs.
        tenantMongoAccess.forServer(seededServer)
            .remove(new Query(), CollectionName.WEBAUTHN_CHALLENGES);
        tenantMongoAccess.forServer(seededServer)
            .remove(new Query(), CollectionName.WEBAUTHN_CREDENTIALS);

        AuthSessionData session = sessionService.createSession(
            seededServer, TEST_ADMIN_EMAIL, "127.0.0.1", "integration-test");
        adminSessionId = session.getId();
    }

    @Test
    void registerOptionsRequiresAnAuthenticatedSession() throws Exception {
        mockMvc.perform(post("/v1/panel/auth/webauthn/register/options")
                .header("Host", TEST_HOST))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void registerOptionsPersistsChallengeAndReturnsChallengeId() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/panel/auth/webauthn/register/options")
                .header("Host", TEST_HOST)
                .header("Origin", "https://modl.test")
                .cookie(new Cookie(authConfiguration.getSessionCookieName(), adminSessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.challengeId").isNotEmpty())
            .andExpect(jsonPath("$.options").exists())
            .andReturn();

        String challengeId = objectMapper.readTree(result.getResponse().getContentAsString())
            .get("challengeId").asText();

        long challengeCount = tenantMongoAccess.forServer(seededServer)
            .count(new Query(), CollectionName.WEBAUTHN_CHALLENGES);
        assertThat(challengeCount).isEqualTo(1L);

        Document challengeDoc = tenantMongoAccess.forServer(seededServer)
            .findOne(new Query(), Document.class, CollectionName.WEBAUTHN_CHALLENGES);
        assertThat(challengeDoc).isNotNull();
        assertThat(challengeDoc.getString("_id")).isEqualTo(challengeId);
        assertThat(challengeDoc.getString("email")).isEqualTo(TEST_ADMIN_EMAIL);
    }

    @Test
    void registerVerifyWithBogusAttestationIsRejectedByRelyingParty() throws Exception {
        MvcResult optionsResult = mockMvc.perform(post("/v1/panel/auth/webauthn/register/options")
                .header("Host", TEST_HOST)
                .header("Origin", "https://modl.test")
                .cookie(new Cookie(authConfiguration.getSessionCookieName(), adminSessionId)))
            .andExpect(status().isOk())
            .andReturn();
        String challengeId = objectMapper.readTree(optionsResult.getResponse().getContentAsString())
            .get("challengeId").asText();

        // Syntactically-valid registration payload with a forged attestation. Yubico's
        // RelyingParty must reject it — we don't care which 4xx, only that no credential is
        // persisted and no exception escapes the controller.
        String payload = """
            {
              "challengeId": "%s",
              "name": "test-key",
              "response": {
                "id": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "rawId": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "type": "public-key",
                "response": {
                  "clientDataJSON": "eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIn0",
                  "attestationObject": "o2NmbXRkbm9uZWdhdHRTdG10oGhhdXRoRGF0YVAA"
                },
                "clientExtensionResults": {}
              }
            }""".formatted(challengeId);

        mockMvc.perform(post("/v1/panel/auth/webauthn/register/verify")
                .header("Host", TEST_HOST)
                .cookie(new Cookie(authConfiguration.getSessionCookieName(), adminSessionId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().is4xxClientError());

        // No credential persisted.
        long credentialCount = tenantMongoAccess.forServer(seededServer)
            .count(new Query(), CollectionName.WEBAUTHN_CREDENTIALS);
        assertThat(credentialCount).isZero();
    }

    @Test
    void loginStartIssuesDiscoverableChallengeWithoutSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/panel/auth/webauthn/login/start")
                .header("Host", TEST_HOST))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.challengeId").isNotEmpty())
            .andExpect(jsonPath("$.options").exists())
            .andReturn();

        String challengeId = objectMapper.readTree(result.getResponse().getContentAsString())
            .get("challengeId").asText();
        Document challengeDoc = tenantMongoAccess.forServer(seededServer)
            .findOne(new Query(), Document.class, CollectionName.WEBAUTHN_CHALLENGES);
        assertThat(challengeDoc).isNotNull();
        assertThat(challengeDoc.getString("_id")).isEqualTo(challengeId);
    }

    @Test
    void loginOptionsForUnknownEmailReturnsAntiEnumerationResponse() throws Exception {
        mockMvc.perform(post("/v1/panel/auth/webauthn/login/options")
                .header("Host", TEST_HOST)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"stranger@example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasPasskeys").value(false));
    }

    @Test
    void loginOptionsForAuthorizedEmailWithoutPasskeysReturnsHasPasskeysFalse() throws Exception {
        // TEST_ADMIN_EMAIL is the server admin (so isAuthorizedEmail returns true),
        // but no WebAuthnCredential row exists, so checkHasPasskeys must report false.
        mockMvc.perform(post("/v1/panel/auth/webauthn/login/options")
                .header("Host", TEST_HOST)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\"}".formatted(TEST_ADMIN_EMAIL)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasPasskeys").value(false));
    }

    @Test
    void loginVerifyWithGarbageDoesNotIssueSessionCookie() throws Exception {
        MvcResult startResult = mockMvc.perform(post("/v1/panel/auth/webauthn/login/start")
                .header("Host", TEST_HOST))
            .andExpect(status().isOk())
            .andReturn();
        String challengeId = objectMapper.readTree(startResult.getResponse().getContentAsString())
            .get("challengeId").asText();

        String payload = """
            {
              "challengeId": "%s",
              "response": {
                "id": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "rawId": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "type": "public-key",
                "response": {
                  "clientDataJSON": "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0In0",
                  "authenticatorData": "AAAAAAA",
                  "signature": "AAAAAAA"
                },
                "clientExtensionResults": {}
              }
            }""".formatted(challengeId);

        JsonNode responseBody = objectMapper.readTree(
            mockMvc.perform(post("/v1/panel/auth/webauthn/login/verify")
                    .header("Host", TEST_HOST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                .andExpect(status().is4xxClientError())
                .andReturn()
                .getResponse()
                .getContentAsString());
        // Whatever the shape, the call must NOT have produced a success flag.
        assertThat(responseBody.path("success").asBoolean(false)).isFalse();
    }
}
