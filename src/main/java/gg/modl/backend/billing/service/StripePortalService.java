package gg.modl.backend.billing.service;

import com.stripe.exception.StripeException;
import com.stripe.model.billingportal.Session;
import com.stripe.param.billingportal.SessionCreateParams;
import gg.modl.backend.infrastructure.config.ModlProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StripePortalService {
    private final ModlProperties modlProperties;

    public Session createPortalSession(String customerId, String subdomain) throws StripeException {
        String returnUrl = String.format("https://%s.%s/panel/settings", subdomain, modlProperties.getDomain());

        SessionCreateParams params = SessionCreateParams.builder()
            .setCustomer(customerId)
            .setReturnUrl(returnUrl)
            .build();

        return Session.create(params);
    }
}
