package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.github.temporalrift.workbench.TestSecurityConfig;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase.Perspective;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ListCasesUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ReproduceCaseUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.config.SecurityConfig;

@WebMvcTest(
        controllers = CaseController.class,
        properties = {
            "workbench.ui.oidc-issuer-url=http://localhost:9000/default",
            "workbench.ui.oidc-client-id=simulation-workbench-ui"
        })
@Import({SecurityConfig.class, TestSecurityConfig.class})
class CaseReplaySecurityIT {

    private static final String READ = "SCOPE_simulation:read";
    private static final String OBSERVE = "SCOPE_simulation:observe";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetCaseUseCase getCaseUseCase;

    @MockitoBean
    private GetCaseReplayUseCase getCaseReplayUseCase;

    @MockitoBean
    private ReproduceCaseUseCase reproduceCaseUseCase;

    @MockitoBean
    private ListCasesUseCase listCasesUseCase;

    @Test
    @DisplayName("Given only simulation:read, when a PLAYER replay is requested, then returns 200")
    void givenReadScopeOnly_whenPlayerReplayRequested_thenReturns200() throws Exception {
        stubReplay(Perspective.PLAYER);

        replay("PLAYER", READ).andExpect(status().isOk());
    }

    @Test
    @DisplayName(
            "Given only simulation:read, when an OBSERVER replay is requested, then returns 403 INSUFFICIENT_SCOPE")
    void givenReadScopeOnly_whenObserverReplayRequested_thenReturns403InsufficientScope() throws Exception {
        stubReplay(Perspective.OBSERVER);

        replay("OBSERVER", READ)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
    }

    @Test
    @DisplayName("Given simulation:read and simulation:observe, when an OBSERVER replay is requested, then returns 200")
    void givenObserveScope_whenObserverReplayRequested_thenReturns200() throws Exception {
        stubReplay(Perspective.OBSERVER);

        replay("OBSERVER", READ, OBSERVE).andExpect(status().isOk());
    }

    private void stubReplay(Perspective perspective) {
        when(getCaseReplayUseCase.handle(any()))
                .thenReturn(new GetCaseReplayUseCase.ReplayPage(
                        UUID.randomUUID(), "sha256:digest", perspective, List.of(), null));
    }

    private ResultActions replay(String perspective, String... authorities) throws Exception {
        var granted = java.util.Arrays.stream(authorities)
                .<org.springframework.security.core.GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        return mockMvc.perform(get("/api/v1/runs/{runId}/cases/{caseId}/replay", UUID.randomUUID(), UUID.randomUUID())
                .param("perspective", perspective)
                .param("seatIndex", "PLAYER".equals(perspective) ? "0" : "")
                .with(jwt().authorities(granted)));
    }
}
