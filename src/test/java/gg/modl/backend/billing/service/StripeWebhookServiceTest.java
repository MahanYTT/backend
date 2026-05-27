package gg.modl.backend.billing.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.model.Event;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.StripeWebhookEventMongoRepository;
import gg.modl.backend.server.service.ServerMutationHelper;
import java.util.Date;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceTest {
    @Test
    void duplicateEventIdIsIgnoredBeforeBillingMutation() {
        StripeService stripeService = mock(StripeService.class);
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        UsageTrackingService usageTrackingService = mock(UsageTrackingService.class);
        ServerMutationHelper serverMutationHelper = mock(ServerMutationHelper.class);
        StripeWebhookEventMongoRepository webhookEventRepository = mock(StripeWebhookEventMongoRepository.class);
        StripeWebhookService service = new StripeWebhookService(
            stripeService,
            serverRepository,
            usageTrackingService,
            serverMutationHelper,
            webhookEventRepository
        );
        Event event = new Event();
        event.setId("evt_123");
        event.setType("customer.subscription.deleted");
        when(webhookEventRepository.markProcessing(any(), any(), any(Date.class))).thenReturn(false);

        service.processEvent(event);

        verify(serverRepository, never()).findByStripeSubscriptionId(any());
        verify(serverMutationHelper, never()).mutate(any(), any());
    }

    @Test
    void failedHandlerMarksEventFailedSoRetryCanClaimLater() {
        StripeService stripeService = mock(StripeService.class);
        ServerMongoRepository serverRepository = mock(ServerMongoRepository.class);
        UsageTrackingService usageTrackingService = mock(UsageTrackingService.class);
        ServerMutationHelper serverMutationHelper = mock(ServerMutationHelper.class);
        StripeWebhookEventMongoRepository webhookEventRepository = mock(StripeWebhookEventMongoRepository.class);
        StripeWebhookService service = new StripeWebhookService(
            stripeService,
            serverRepository,
            usageTrackingService,
            serverMutationHelper,
            webhookEventRepository
        );
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("evt_failed");
        when(event.getType()).thenReturn("customer.subscription.deleted");
        when(webhookEventRepository.markProcessing(any(), any(), any(Date.class))).thenReturn(true);

        Assertions.assertThrows(NullPointerException.class, () -> service.processEvent(event));

        verify(webhookEventRepository).markFailed(eq("evt_failed"), any(Date.class), any());
    }
}
