package gg.modl.backend.infrastructure.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.proto.modl.v1.ApiError;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Test
    void v3RateLimitRejectionReturnsBinaryApiError() throws Exception {
        RateLimitConfig rateLimitConfig = mock(RateLimitConfig.class);
        Bucket bucket = mock(Bucket.class);
        when(rateLimitConfig.getTierForPath("/v3/minecraft/players/sync", "POST"))
            .thenReturn(RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD);
        when(rateLimitConfig.resolveBucket("127.0.0.1:MINECRAFT_STANDARD", RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD))
            .thenReturn(bucket);
        when(bucket.tryConsumeAndReturnRemaining(1))
            .thenReturn(ConsumptionProbe.rejected(0, 2_000_000_000L, 2_000_000_000L));

        RateLimitFilter filter = new RateLimitFilter(rateLimitConfig, new ProtobufErrorResponseWriter());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v3/minecraft/players/sync");
        request.addHeader("Accept", ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE, response.getContentType());
        ApiError error = ApiError.parseFrom(response.getContentAsByteArray());
        assertEquals(429, error.getStatusCode());
        assertEquals("RESOURCE_EXHAUSTED", error.getCode());
        assertEquals("Rate limit exceeded", error.getMessage());
        assertNull(chain.getRequest());
    }

    @Test
    void v1RateLimitRejectionPreservesJsonErrorBody() throws Exception {
        RateLimitConfig rateLimitConfig = mock(RateLimitConfig.class);
        Bucket bucket = mock(Bucket.class);
        when(rateLimitConfig.getTierForPath("/v1/minecraft/players/sync", "POST"))
            .thenReturn(RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD);
        when(rateLimitConfig.resolveBucket("127.0.0.1:MINECRAFT_STANDARD", RateLimitConfig.RateLimitTier.MINECRAFT_STANDARD))
            .thenReturn(bucket);
        when(bucket.tryConsumeAndReturnRemaining(1))
            .thenReturn(ConsumptionProbe.rejected(0, 2_000_000_000L, 2_000_000_000L));

        RateLimitFilter filter = new RateLimitFilter(rateLimitConfig, new ProtobufErrorResponseWriter());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/minecraft/players/sync");
        request.addHeader("Accept", ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertTrue(response.getContentAsString().contains("\"error\":\"Rate limit exceeded\""));
        assertTrue(response.getContentAsString().contains("\"retryAfterSeconds\":2"));
        assertNull(chain.getRequest());
    }
}
