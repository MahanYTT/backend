package gg.modl.backend.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stripe.model.Event;
import gg.modl.backend.billing.config.StripeConfiguration;
import gg.modl.backend.billing.service.StripeService;
import gg.modl.backend.billing.service.StripeWebhookService;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies real Stripe webhook signature handling end-to-end through the controller. The pure
 * unit test ({@code StripeWebhookServiceTest}) mocks {@link Event} and never exercises
 * {@code com.stripe.net.Webhook.constructEvent}, so signature checks, replay tolerance, and
 * payload tampering were previously untested. This slice loads the real controller, real
 * {@link StripeConfiguration}, and real Stripe SDK signature verification.
 *
 * <p>Spring Security filters are disabled here because the webhook endpoint is intentionally
 * unauthenticated at the security-filter level (Stripe authenticates via the signature header,
 * which the controller verifies). End-to-end filter behavior is covered separately.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@org.testcontainers.junit.jupiter.Testcontainers
@TestPropertySource(properties = {
    "modl.stripe.secret-key=sk_test_dummy",
    "modl.stripe.webhook-secret=" + StripeWebhookControllerIntegrationTest.WEBHOOK_SECRET,
    "modl.stripe.price-id=price_test_dummy"
})
class StripeWebhookControllerIntegrationTest extends gg.modl.backend.support.AbstractMongoIntegrationTest {

    static final String WEBHOOK_SECRET = "whsec_modl_test_known_secret_value";

    private static final String VALID_PAYLOAD = """
        {
          "id": "evt_1NLqUk2eZvKYlo2C7uSx3wMR",
          "object": "event",
          "type": "checkout.session.completed",
          "api_version": "2024-04-10",
          "created": 1700000000,
          "data": {
            "object": {
              "id": "cs_test_123",
              "object": "checkout.session",
              "customer": "cus_test_123",
              "subscription": "sub_test_123"
            }
          }
        }""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StripeService stripeService;

    @MockitoBean
    private StripeWebhookService stripeWebhookService;

    @BeforeEach
    void setUp() {
        when(stripeService.isConfigured()).thenReturn(true);
    }

    @Test
    void validSignatureAcceptsAndParsesEvent() throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String header = stripeSignature(VALID_PAYLOAD, timestamp, WEBHOOK_SECRET);

        mockMvc.perform(post(RESTMappingV1.WEBHOOKS_STRIPE)
                .header("Stripe-Signature", header)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PAYLOAD))
            .andExpect(status().isOk());

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(stripeWebhookService).processEvent(eventCaptor.capture());
        Event parsed = eventCaptor.getValue();
        assertThat(parsed.getId()).isEqualTo("evt_1NLqUk2eZvKYlo2C7uSx3wMR");
        assertThat(parsed.getType()).isEqualTo("checkout.session.completed");
    }

    @Test
    void tamperedPayloadIsRejectedAsUnauthorized() throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String header = stripeSignature(VALID_PAYLOAD, timestamp, WEBHOOK_SECRET);

        String tamperedPayload = VALID_PAYLOAD.replace("cs_test_123", "cs_test_attacker");

        mockMvc.perform(post(RESTMappingV1.WEBHOOKS_STRIPE)
                .header("Stripe-Signature", header)
                .contentType(MediaType.APPLICATION_JSON)
                .content(tamperedPayload))
            .andExpect(status().isUnauthorized());

        verify(stripeWebhookService, never()).processEvent(any());
    }

    @Test
    void wrongSecretIsRejectedAsUnauthorized() throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String header = stripeSignature(VALID_PAYLOAD, timestamp, "whsec_wrong_secret");

        mockMvc.perform(post(RESTMappingV1.WEBHOOKS_STRIPE)
                .header("Stripe-Signature", header)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PAYLOAD))
            .andExpect(status().isUnauthorized());

        verify(stripeWebhookService, never()).processEvent(any());
    }

    @Test
    void expiredTimestampIsRejectedAsUnauthorized() throws Exception {
        // Stripe's default tolerance is 300 seconds; one hour ago is far outside the window.
        long timestamp = (System.currentTimeMillis() / 1000) - 3600;
        String header = stripeSignature(VALID_PAYLOAD, timestamp, WEBHOOK_SECRET);

        mockMvc.perform(post(RESTMappingV1.WEBHOOKS_STRIPE)
                .header("Stripe-Signature", header)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PAYLOAD))
            .andExpect(status().isUnauthorized());

        verify(stripeWebhookService, never()).processEvent(any());
    }

    @Test
    void malformedSignatureHeaderIsRejectedAsUnauthorized() throws Exception {
        mockMvc.perform(post(RESTMappingV1.WEBHOOKS_STRIPE)
                .header("Stripe-Signature", "this-is-not-a-real-signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PAYLOAD))
            .andExpect(status().isUnauthorized());

        verify(stripeWebhookService, never()).processEvent(any());
    }

    @Test
    void missingSignatureHeaderIsRejectedBeforeReachingController() throws Exception {
        mockMvc.perform(post(RESTMappingV1.WEBHOOKS_STRIPE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PAYLOAD))
            .andExpect(status().is4xxClientError());

        verify(stripeWebhookService, never()).processEvent(any());
    }

    @Test
    void stripeNotConfiguredReturnsServiceUnavailable() throws Exception {
        when(stripeService.isConfigured()).thenReturn(false);
        long timestamp = System.currentTimeMillis() / 1000;
        String header = stripeSignature(VALID_PAYLOAD, timestamp, WEBHOOK_SECRET);

        mockMvc.perform(post(RESTMappingV1.WEBHOOKS_STRIPE)
                .header("Stripe-Signature", header)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PAYLOAD))
            .andExpect(status().isServiceUnavailable());

        verify(stripeWebhookService, never()).processEvent(any());
    }

    private static String stripeSignature(String payload, long timestamp, String secret) {
        String signedPayload = timestamp + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "t=" + timestamp + ",v1=" + hex;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute Stripe signature", e);
        }
    }
}
