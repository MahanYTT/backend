package gg.modl.backend.panel;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.StagingCredentials;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PanelBillingApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(StagingCredentials.isPanelApiAvailable(), StagingCredentials.panelApiUnavailableReason());
        api = new ApiClient();
    }

    @Test
    void getBillingStatus() throws Exception {
        var response = api.panelGet("/v1/panel/billing/status");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getUsage() throws Exception {
        var response = api.panelGet("/v1/panel/billing/usage");
        JsonHelper.assertStatus(response, 200);
    }

    @Disabled("Skipped: would create a real Stripe checkout session")
    @Test
    void createCheckoutSession() throws Exception {}

    @Disabled("Skipped: would create a real Stripe portal session")
    @Test
    void createPortalSession() throws Exception {}

    @Disabled("Skipped: would cancel real subscription on staging")
    @Test
    void cancelSubscription() throws Exception {}

    @Disabled("Skipped: would reactivate real subscription on staging")
    @Test
    void resubscribe() throws Exception {}

    @Disabled("Skipped: would modify usage billing settings on staging")
    @Test
    void updateUsageSettings() throws Exception {}

    @Disabled("Skipped: would modify storage limits on staging")
    @Test
    void updateStorageLimit() throws Exception {}
}

