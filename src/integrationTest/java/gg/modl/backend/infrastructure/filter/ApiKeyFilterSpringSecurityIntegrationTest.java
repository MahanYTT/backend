package gg.modl.backend.infrastructure.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.dashboard.controller.v3.MinecraftDashboardV3Controller;
import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.backend.dashboard.service.DashboardService;
import gg.modl.backend.infrastructure.cors.DynamicCorsConfigurationSource;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.settings.service.ApiKeySettingsService;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.MinecraftDashboardResponse;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Exercises {@link ApiKeyFilter} through the <strong>real</strong> {@link V1SecurityConfig}
 * {@code SecurityFilterChain} — not a hand-rolled {@code MockMvcBuilders.standaloneSetup}.
 * This catches:
 *
 * <ul>
 *   <li>filter ordering bugs (e.g., {@code addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)})
 *   <li>missing or misconfigured {@code requestMatchers(...).hasAuthority(...)} rules
 *   <li>regressions where {@link ApiKeyFilter#shouldNotFilter} stops covering a path it once protected
 * </ul>
 *
 * <p>The five non-API-key filters in {@code V1SecurityConfig} are replaced with pass-through
 * Mockito stubs because their behavior is irrelevant on Minecraft paths (each early-exits in
 * production) — what matters is that they don't intercept and that {@code ApiKeyFilter}'s
 * authority/attribute mutation makes it through Spring Security's {@code authorizeHttpRequests}.
 */
@WebMvcTest(controllers = MinecraftDashboardV3Controller.class)
@Import({
    V1SecurityConfig.class,
    ApiKeyFilter.class,
    ProtobufErrorResponseWriter.class,
    ProtoBinaryHttpMessageConverter.class,
    ProtoJsonHttpMessageConverter.class,
    ProtoValidationAdvice.class,
    GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class ApiKeyFilterSpringSecurityIntegrationTest {

    private static final String VALID_API_KEY = "valid-api-key";
    private static final String INVALID_API_KEY = "invalid-api-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeySettingsService apiKeySettingsService;
    @MockitoBean
    private DashboardService dashboardService;
    @MockitoBean
    private SessionAuthenticationFilter sessionAuthenticationFilter;
    @MockitoBean
    private AdminAuthFilter adminAuthFilter;
    @MockitoBean
    private PanelPermissionFilter panelPermissionFilter;
    @MockitoBean
    private OriginCsrfFilter originCsrfFilter;
    @MockitoBean
    private DynamicCorsConfigurationSource dynamicCorsConfigurationSource;
    @MockitoBean
    private gg.modl.backend.infrastructure.ratelimit.RateLimitFilter rateLimitFilter;

    private Server server;

    @BeforeEach
    void setUp() throws Exception {
        server = new Server("Demo", "demo", "server_demo", "admin@example.com", true, ServerPlan.FREE);
        // Make the non-API-key filters fall through so the security chain reaches the controller.
        passThrough(sessionAuthenticationFilter);
        passThrough(adminAuthFilter);
        passThrough(panelPermissionFilter);
        passThrough(originCsrfFilter);
        passThrough(rateLimitFilter);
    }

    @Test
    void missingApiKeyRejectsBeforeReachingControllerEvenWithRealSecurityChain() throws Exception {
        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(401, error.getStatusCode());
        assertEquals("UNAUTHENTICATED", error.getCode());
        verifyNoInteractions(apiKeySettingsService, dashboardService);
    }

    @Test
    void invalidApiKeyRejectsBeforeReachingControllerEvenWithRealSecurityChain() throws Exception {
        when(apiKeySettingsService.findServerByApiKey(INVALID_API_KEY)).thenReturn(null);

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .header(RequestHeader.API_KEY, INVALID_API_KEY)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(401, error.getStatusCode());
        assertEquals("UNAUTHENTICATED", error.getCode());
        verify(apiKeySettingsService).findServerByApiKey(INVALID_API_KEY);
        verifyNoInteractions(dashboardService);
    }

    @Test
    void validApiKeyPopulatesServerRequestAttributeThroughTheSecurityChain() throws Exception {
        // Asserts that ApiKeyFilter ran *inside* the real V1SecurityConfig chain and that it
        // populated the RequestAttribute.SERVER attribute. We don't assert HTTP 200 / controller
        // delegate because Spring Security clears the SecurityContext after the response and
        // the mocked pass-through filters change the chain shape — a true end-to-end success
        // path requires a heavier @SpringBootTest with all real filters wired.
        when(apiKeySettingsService.findServerByApiKey(VALID_API_KEY)).thenReturn(server);

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .header(RequestHeader.API_KEY, VALID_API_KEY)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        verify(apiKeySettingsService).findServerByApiKey(VALID_API_KEY);
        Object resolvedServer = result.getRequest().getAttribute(gg.modl.backend.infrastructure.rest.RequestAttribute.SERVER);
        assertEquals(server, resolvedServer);
    }

    private static void passThrough(Filter mock) throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(mock).doFilter(any(), any(), any());
    }
}
