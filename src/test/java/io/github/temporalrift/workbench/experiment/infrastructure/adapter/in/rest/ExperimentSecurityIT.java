package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.TestSecurityConfig;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.config.SecurityConfig;

@WebMvcTest(controllers = ExperimentController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
class ExperimentSecurityIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateExperimentUseCase createExperimentUseCase;

    @Test
    @DisplayName("Given no Authorization header, when createExperiment is called, then returns 401")
    void givenNoAuthorizationHeader_whenCreateExperimentCalled_thenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/experiments")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    @DisplayName("Given only simulation:read, when createExperiment is called, then returns 403")
    void givenReadScopeOnly_whenCreateExperimentCalled_thenReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/experiments")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read")))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
