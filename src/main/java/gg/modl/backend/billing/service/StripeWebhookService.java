package gg.modl.backend.billing.service;

import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import gg.modl.backend.database.mongo.repository.ServerMongoRepository;
import gg.modl.backend.database.mongo.repository.StripeWebhookEventMongoRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.data.SubscriptionStatus;
import gg.modl.backend.server.service.ServerMutationHelper;
import java.util.Date;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookService {
    private final StripeService stripeService;
    private final ServerMongoRepository serverRepository;
    private final UsageTrackingService usageTrackingService;
    private final ServerMutationHelper serverMutationHelper;
    private final StripeWebhookEventMongoRepository webhookEventRepository;

    public void processEvent(Event event) {
        if (!webhookEventRepository.markProcessing(event.getId(), event.getType(), new Date())) {
            log.info("Ignoring duplicate Stripe webhook event {}", event.getId());
            return;
        }
        try {
            switch (event.getType()) {
                case "checkout.session.completed" -> handleCheckoutCompleted(event);
                case "customer.subscription.created" -> handleSubscriptionCreated(event);
                case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
                case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
                case "invoice.payment_failed" -> handlePaymentFailed(event);
                case "invoice.payment_succeeded" -> handlePaymentSucceeded(event);
                default -> log.debug("Unhandled event type: {}", event.getType());
            }
            webhookEventRepository.markProcessed(event.getId(), new Date());
        } catch (RuntimeException exception) {
            webhookEventRepository.markFailed(event.getId(), new Date(), exception.getMessage());
            throw exception;
        }
    }

    private void handleCheckoutCompleted(Event event) {
        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (!(stripeObject instanceof Session session)) {
            return;
        }

        if (session.getCustomer() == null || session.getSubscription() == null) {
            return;
        }

        Server server = findServerByCustomerId(session.getCustomer());
        if (server == null) {
            log.warn("No server found for customer: {}", session.getCustomer());
            return;
        }

        try {
            Subscription subscription = stripeService.retrieveSubscription(session.getSubscription());
            Date periodStart = stripeService.extractPeriodStart(subscription);
            Date periodEnd = stripeService.extractPeriodEnd(subscription);
            usageTrackingService.applyBillingPeriodRollover(server, periodStart, periodEnd);
            serverMutationHelper.mutate(server, current -> {
                current.setStripeSubscriptionId(session.getSubscription());
                current.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
                current.setPlan(ServerPlan.PREMIUM);
                current.setCurrentPeriodStart(periodStart);
                current.setCurrentPeriodEnd(periodEnd);
            });
        } catch (Exception exception) {
            log.error("Error retrieving subscription details", exception);
        }
    }

    private Server findServerByCustomerId(String customerId) {
        return serverRepository.findByStripeCustomerId(customerId).orElse(null);
    }

    private void handleSubscriptionCreated(Event event) {
        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (!(stripeObject instanceof Subscription subscription)) {
            return;
        }

        Server server = findServerByCustomerId(subscription.getCustomer());
        if (server == null) {
            return;
        }

        Date periodStart = stripeService.extractPeriodStart(subscription);
        Date periodEnd = stripeService.extractPeriodEnd(subscription);
        usageTrackingService.applyBillingPeriodRollover(server, periodStart, periodEnd);
        serverMutationHelper.mutate(server, current -> {
            current.setStripeSubscriptionId(subscription.getId());
            current.setSubscriptionStatus(parseSubscriptionStatus(subscription.getStatus()));
            current.setPlan(planForSubscriptionStatus(subscription.getStatus()));
            current.setCurrentPeriodStart(periodStart);
            current.setCurrentPeriodEnd(periodEnd);
        });
    }

    private ServerPlan planForSubscriptionStatus(String status) {
        return isFreeStatus(status) ? ServerPlan.FREE : ServerPlan.PREMIUM;
    }

    private boolean isFreeStatus(String status) {
        return "past_due".equals(status)
               || "unpaid".equals(status)
               || "incomplete".equals(status)
               || "incomplete_expired".equals(status);
    }

    private SubscriptionStatus parseSubscriptionStatus(String status) {
        try {
            return SubscriptionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            log.warn("Unknown subscription status from Stripe: {}, defaulting to inactive", status);
            return SubscriptionStatus.INACTIVE;
        }
    }

    private void handleSubscriptionUpdated(Event event) {
        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (!(stripeObject instanceof Subscription subscription)) {
            return;
        }

        Server server = findServerBySubscriptionId(subscription.getId());
        if (server == null) {
            log.warn("No server found for subscription: {}", subscription.getId());
            return;
        }

        String effectiveStatus = stripeService.getEffectiveStatus(subscription);
        Date periodStartDate = stripeService.extractPeriodStart(subscription);
        Date periodEndDate = stripeService.extractPeriodEnd(subscription);
        usageTrackingService.applyBillingPeriodRollover(server, periodStartDate, periodEndDate);
        serverMutationHelper.mutate(server, current -> {
            current.setSubscriptionStatus(parseSubscriptionStatus(effectiveStatus));
            if (isPremiumStatus(effectiveStatus)) {
                current.setPlan(ServerPlan.PREMIUM);
            } else if (isFreeStatus(effectiveStatus)) {
                current.setPlan(ServerPlan.FREE);
            }

            if (periodStartDate != null) {
                current.setCurrentPeriodStart(periodStartDate);
            }
            if (periodEndDate != null) {
                current.setCurrentPeriodEnd(periodEndDate);
            }
        });
    }

    private Server findServerBySubscriptionId(String subscriptionId) {
        return serverRepository.findByStripeSubscriptionId(subscriptionId).orElse(null);
    }

    private boolean isPremiumStatus(String status) {
        return "active".equals(status) || "trialing".equals(status) || "paused".equals(status);
    }

    private void handleSubscriptionDeleted(Event event) {
        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (!(stripeObject instanceof Subscription subscription)) {
            return;
        }

        Server server = findServerBySubscriptionId(subscription.getId());
        if (server == null) {
            return;
        }

        serverMutationHelper.mutate(server, current -> {
            current.setSubscriptionStatus(SubscriptionStatus.CANCELED);
            current.setPlan(ServerPlan.FREE);
            current.setCurrentPeriodEnd(null);
        });
        usageTrackingService.resetUsageCounters(server.getId());
    }

    private void handlePaymentFailed(Event event) {
        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (!(stripeObject instanceof Invoice invoice) || invoice.getCustomer() == null) {
            return;
        }

        Server server = findServerByCustomerId(invoice.getCustomer());
        if (server == null) {
            return;
        }

        serverMutationHelper.mutate(server, current -> {
            current.setSubscriptionStatus(SubscriptionStatus.PAST_DUE);
            current.setPlan(ServerPlan.FREE);
        });
    }

    private void handlePaymentSucceeded(Event event) {
        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
        if (!(stripeObject instanceof Invoice invoice) || invoice.getCustomer() == null) {
            return;
        }

        Server server = findServerByCustomerId(invoice.getCustomer());
        if (server == null) {
            return;
        }

        if (server.getStripeSubscriptionId() != null) {
            try {
                Subscription subscription = stripeService.retrieveSubscription(server.getStripeSubscriptionId());
                usageTrackingService.applyBillingPeriodRollover(
                    server,
                    stripeService.extractPeriodStart(subscription),
                    stripeService.extractPeriodEnd(subscription)
                );
            } catch (Exception exception) {
                log.error("Error retrieving subscription details on payment success", exception);
            }
        }

        if (server.getSubscriptionStatus() != SubscriptionStatus.PAST_DUE) {
            return;
        }

        serverMutationHelper.mutate(server, current -> {
            current.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
            current.setPlan(ServerPlan.PREMIUM);
        });
    }
}
