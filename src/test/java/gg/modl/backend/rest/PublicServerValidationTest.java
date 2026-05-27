package gg.modl.backend.rest;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.server.ServerService;
import gg.modl.backend.server.controller.PublicServerController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class PublicServerValidationTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ServerService serverService = mock(ServerService.class);
        lenient().when(serverService.doesServerExist(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ServerService.ServerExistResult(false, false, false));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new PublicServerController(serverService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .build();
    }

    @Test
    void registerRejectsInvalidBodyWithCentralizedError() throws Exception {
        mockMvc.perform(post(RESTMappingV1.PUBLIC_SERVER + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Invalid data provided."))
            .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void registerRejectsMalformedJsonWithCentralizedError() throws Exception {
        mockMvc.perform(post(RESTMappingV1.PUBLIC_SERVER + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Invalid data provided."))
            .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void legacyRegisterEndpointIsGoneForValidRequests() throws Exception {
        mockMvc.perform(post(RESTMappingV1.PUBLIC_SERVER + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "admin@example.com",
                      "serverName": "Example Server",
                      "customDomain": "example",
                      "turnstileToken": "token"
                    }
                    """))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void legacyAvailabilityEndpointIsGone() throws Exception {
        mockMvc.perform(post(RESTMappingV1.PUBLIC_SERVER + "/check-availability")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "admin@example.com",
                      "serverName": "Example Server",
                      "customDomain": "example"
                    }
                    """))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.message").value("Use /v1/public/registration/check-availability instead."));
    }
}
