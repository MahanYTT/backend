package gg.modl.backend.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.model.checkout.Session;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.server.service.ServerMutationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock
    private StripeService stripeService;

    @Mock
    private ServerMutationHelper serverMutationHelper;

    @Mock
    private PermissionService permissionService;

    @Mock
    private UsageTrackingService usageTrackingService;

    private BillingService billingService;

    @BeforeEach
    void setUp() {
        billingService = new BillingService(stripeService, serverMutationHelper, permissionService, usageTrackingService);
    }

    @Test
    void createCheckoutSessionPersistsStripeCustomerIdThroughRepository() throws Exception {
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        server.setId("server-1");

        Session session = mock(Session.class);
        when(stripeService.createCustomer(server)).thenReturn("cus_123");
        when(stripeService.createCheckoutSession("cus_123", server.getCustomDomain())).thenReturn(session);
        when(session.getId()).thenReturn("sess_123");
        when(session.getUrl()).thenReturn("https://checkout.example.com");

        doAnswer(invocation -> {
            java.util.function.Consumer<Server> mutator = invocation.getArgument(1);
            mutator.accept(invocation.getArgument(0));
            return null;
        }).when(serverMutationHelper).mutate(any(Server.class), any());

        billingService.createCheckoutSession(server);

        verify(serverMutationHelper).mutate(any(Server.class), any());
        assertEquals("cus_123", server.getStripeCustomerId());
    }
}
