package gg.modl.backend.billing.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import gg.modl.backend.billing.dto.response.BillingStatusResponse;
import gg.modl.backend.billing.dto.response.CancelResponse;
import gg.modl.backend.billing.dto.response.CheckoutSessionResponse;
import gg.modl.backend.billing.dto.response.PortalSessionResponse;
import gg.modl.backend.billing.dto.response.ResubscribeResponse;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ExternalServiceException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.role.service.PermissionService;
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
public class BillingService {
    private final StripeService stripeService;
    private final ServerMutationHelper serverMutationHelper;
    private final PermissionService permissionService;
    private final UsageTrackingService usageTrackingService;

    public void requireStripeConfigured() {
        if (!stripeService.isConfigured()) {
            throw new ExternalServiceException("Billing service unavailable. Stripe not configured.");
        }
    }

    public void requireSuperAdmin(Server server, String email) {
        if (email == null || !permissionService.isSuperAdmin(server, email)) {
            throw new ForbiddenException("Only the super admin can manage billing");
        }
    }

    public CheckoutSessionResponse createCheckoutSession(Server server) {
        try {
            String customerId = server.getStripeCustomerId();

            if (customerId == null || customerId.isBlank()) {
                customerId = stripeService.createCustomer(server);
                String createdCustomerId = customerId;
                serverMutationHelper.mutate(server, current -> current.setStripeCustomerId(createdCustomerId));
            }

            Session session = stripeService.createCheckoutSession(customerId, server.getCustomDomain());
            return new CheckoutSessionResponse(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new ExternalServiceException("Failed to create checkout session", e);
        }
    }

    public PortalSessionResponse createPortalSession(Server server) {
        if (server.getStripeCustomerId() == null || server.getStripeCustomerId().isBlank()) {
            throw new ResourceNotFoundException("Customer ID not found for server");
        }

        try {
            String portalUrl = stripeService.createPortalSessionUrl(server.getStripeCustomerId(), server.getCustomDomain());
            return new PortalSessionResponse(portalUrl);
        } catch (StripeException e) {
            throw new ExternalServiceException("Failed to create portal session", e);
        }
    }

    public CancelResponse cancelSubscription(Server server) {
        if (server.getStripeSubscriptionId() == null || server.getStripeSubscriptionId().isBlank()) {
            throw new ResourceNotFoundException("No active subscription found to cancel");
        }

        try {
            Subscription canceledSubscription = stripeService.cancelSubscription(server.getStripeSubscriptionId());

            Date periodEndDate = server.getCurrentPeriodEnd();
            if (periodEndDate == null) {
                periodEndDate = stripeService.extractPeriodEnd(canceledSubscription);
            }

            Date finalPeriodEndDate = periodEndDate;
            serverMutationHelper.mutate(server, current -> {
                current.setSubscriptionStatus(SubscriptionStatus.CANCELED);
                if (finalPeriodEndDate != null) {
                    current.setCurrentPeriodEnd(finalPeriodEndDate);
                }
            });

            return new CancelResponse(
                true,
                "Subscription cancelled successfully. Access will continue until the end of your current billing period.",
                periodEndDate
            );
        } catch (StripeException e) {
            throw new ExternalServiceException("Failed to cancel subscription", e);
        }
    }

    public BillingStatusResponse getBillingStatus(Server server) {
        SubscriptionStatus currentStatus = server.getSubscriptionStatus();
        Date currentPeriodEnd = server.getCurrentPeriodEnd();
        Date currentPeriodStart = server.getCurrentPeriodStart();

        if (server.getStripeSubscriptionId() != null
            && (currentStatus == null || currentStatus == SubscriptionStatus.ACTIVE || currentStatus == SubscriptionStatus.CANCELED)
            && stripeService.isConfigured()) {
            try {
                Subscription subscription = stripeService.retrieveSubscription(server.getStripeSubscriptionId());
                String effectiveStatus = stripeService.getEffectiveStatus(subscription);
                SubscriptionStatus effectiveSubscriptionStatus = parseSubscriptionStatus(effectiveStatus);
                Date periodStartDate = stripeService.extractPeriodStart(subscription);
                Date periodEndDate = stripeService.extractPeriodEnd(subscription);

                usageTrackingService.applyBillingPeriodRollover(server, periodStartDate, periodEndDate);

                boolean needsUpdate = effectiveSubscriptionStatus != currentStatus
                                      || (periodEndDate != null && (server.getCurrentPeriodEnd() == null || Math.abs(
                    server.getCurrentPeriodEnd().getTime() - periodEndDate.getTime()) > 1000))
                                      || (periodStartDate != null && (server.getCurrentPeriodStart() == null || Math.abs(
                    server.getCurrentPeriodStart().getTime() - periodStartDate.getTime()) > 1000));

                if (needsUpdate) {
                    Date finalPeriodStartDate = periodStartDate;
                    Date finalPeriodEndDate = periodEndDate;
                    serverMutationHelper.mutate(server, current -> {
                        current.setSubscriptionStatus(effectiveSubscriptionStatus);
                        if (finalPeriodStartDate != null) {
                            current.setCurrentPeriodStart(finalPeriodStartDate);
                        }
                        if (finalPeriodEndDate != null) {
                            current.setCurrentPeriodEnd(finalPeriodEndDate);
                        }
                    });
                }

                currentStatus = server.getSubscriptionStatus();
                currentPeriodStart = server.getCurrentPeriodStart();
                currentPeriodEnd = server.getCurrentPeriodEnd();
            } catch (StripeException exception) {
                log.error("Error fetching subscription from Stripe", exception);
            }
        }

        return new BillingStatusResponse(
            server.getPlan() != null ? server.getPlan().name() : null,
            currentStatus != null ? currentStatus.name() : null,
            currentPeriodStart,
            currentPeriodEnd,
            server.getCustomDomainGrandfathered(),
            server.getMaxStorageLimitBytes(),
            server.getMaxAiOverageRequests()
        );
    }

    private SubscriptionStatus parseSubscriptionStatus(String status) {
        try {
            return SubscriptionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            log.warn("Unknown subscription status from Stripe: {}, defaulting to inactive", status);
            return SubscriptionStatus.INACTIVE;
        }
    }

    public ResubscribeResponse resubscribe(Server server) {
        if (server.getSubscriptionStatus() != SubscriptionStatus.CANCELED) {
            throw new ConflictException("No cancelled subscription found to reactivate.");
        }

        try {
            Subscription subscriptionResult;

            if (server.getStripeSubscriptionId() != null) {
                try {
                    Subscription existingSubscription = stripeService.retrieveSubscription(server.getStripeSubscriptionId());

                    if ("active".equals(existingSubscription.getStatus()) && Boolean.TRUE.equals(existingSubscription.getCancelAtPeriodEnd())) {
                        subscriptionResult = stripeService.reactivateSubscription(server.getStripeSubscriptionId());
                    } else if ("canceled".equals(existingSubscription.getStatus())) {
                        subscriptionResult = createNewSubscription(server);
                    } else {
                        throw new ConflictException("Subscription is not in a cancelled state that can be reactivated.");
                    }
                } catch (StripeException exception) {
                    if ("resource_missing".equals(exception.getCode())) {
                        subscriptionResult = createNewSubscription(server);
                    } else {
                        throw exception;
                    }
                }
            } else {
                subscriptionResult = createNewSubscription(server);
            }

            Date periodStartDate = stripeService.extractPeriodStart(subscriptionResult);
            Date periodEndDate = stripeService.extractPeriodEnd(subscriptionResult);
            String subscriptionId = subscriptionResult.getId();
            SubscriptionStatus subscriptionStatus = parseSubscriptionStatus(subscriptionResult.getStatus());

            usageTrackingService.applyBillingPeriodRollover(server, periodStartDate, periodEndDate);
            serverMutationHelper.mutate(server, current -> {
                current.setStripeSubscriptionId(subscriptionId);
                current.setSubscriptionStatus(subscriptionStatus);
                current.setPlan(ServerPlan.PREMIUM);
                if (periodStartDate != null) {
                    current.setCurrentPeriodStart(periodStartDate);
                }
                if (periodEndDate != null) {
                    current.setCurrentPeriodEnd(periodEndDate);
                }
            });

            return new ResubscribeResponse(
                true,
                "Subscription reactivated successfully! Your premium features are now active.",
                new ResubscribeResponse.SubscriptionInfo(
                    subscriptionResult.getId(),
                    subscriptionResult.getStatus(),
                    periodEndDate
                )
            );
        } catch (StripeException e) {
            throw new ExternalServiceException("Failed to resubscribe", e);
        }
    }

    private Subscription createNewSubscription(Server server) throws StripeException {
        if (server.getStripeCustomerId() == null || server.getStripeCustomerId().isBlank()) {
            throw new ResourceNotFoundException("No Stripe customer ID found. Cannot create subscription.");
        }
        return stripeService.createSubscription(server.getStripeCustomerId());
    }
}
