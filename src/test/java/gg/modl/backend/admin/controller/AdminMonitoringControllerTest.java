package gg.modl.backend.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.admin.data.SystemLog;
import gg.modl.backend.admin.service.AdminMonitoringService;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class AdminMonitoringControllerTest {
    private MockMvc mockMvc;
    @Mock private AdminMonitoringService adminMonitoringService;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new AdminMonitoringController(adminMonitoringService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .build();
    }

    @Test
    void createLogRejectsInvalidBodyWithCentralizedError() throws Exception {
        mockMvc.perform(post(RESTMappingV1.ADMIN_MONITORING + "/logs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Missing level\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("Invalid data provided."));
    }

    @Test
    void createLogMapsDtoToDomainAndPersists() throws Exception {
        SystemLog savedLog = new SystemLog();
        savedLog.setId("log-1");
        savedLog.setLevel("warning");
        savedLog.setMessage("CPU spike");
        savedLog.setSource("monitor");
        savedLog.setCategory("infra");
        savedLog.setServerId("507f1f77bcf86cd799439011");
        savedLog.setMetadata(Map.of("cpu", 95));
        savedLog.setTimestamp(new Date());

        when(adminMonitoringService.createLog(any())).thenReturn(savedLog);

        mockMvc.perform(post(RESTMappingV1.ADMIN_MONITORING + "/logs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "level": "warning",
                      "message": "CPU spike",
                      "source": "monitor",
                      "category": "infra",
                      "serverId": "507f1f77bcf86cd799439011",
                      "metadata": {
                        "cpu": 95
                      }
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value("log-1"))
            .andExpect(jsonPath("$.data.level").value("warning"))
            .andExpect(jsonPath("$.data.message").value("CPU spike"));

        verify(adminMonitoringService).createLog(any());
    }
}
