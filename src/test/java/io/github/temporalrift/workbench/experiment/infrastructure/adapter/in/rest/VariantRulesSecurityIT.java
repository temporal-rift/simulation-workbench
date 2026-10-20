package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.temporalrift.workbench.TestSecurityConfig;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.application.port.in.GetParameterSpaceUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.GetVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.ListParameterSpacesUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewVariantRulesUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.config.SecurityConfig;

/** The rules endpoints are reads: they need the read scope, and previewing is a POST that persists nothing. */
@WebMvcTest(
        controllers = {VariantRulesController.class, ParameterSpaceController.class},
        properties = {
            "workbench.ui.oidc-issuer-url=http://localhost:9000/default",
            "workbench.ui.oidc-client-id=simulation-workbench-ui"
        })
@Import({SecurityConfig.class, TestSecurityConfig.class})
class VariantRulesSecurityIT {

    private static final String PREVIEW_BODY = "{\"rulesArtifact\": {\"digest\": \"" + "e".repeat(64)
            + "\"}, \"parameterSpaceVersion\": 1, \"overrides\": {}}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreviewVariantRulesUseCase previewVariantRulesUseCase;

    @MockitoBean
    private GetVariantRulesUseCase getVariantRulesUseCase;

    @MockitoBean
    private ListParameterSpacesUseCase listParameterSpacesUseCase;

    @MockitoBean
    private GetParameterSpaceUseCase getParameterSpaceUseCase;

    @Test
    void previewingWithoutCredentialsIsUnauthorised() throws Exception {
        mockMvc.perform(post("/api/v1/variant-rules-previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREVIEW_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void previewingWithOnlyTheWriteScopeIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/variant-rules-previews")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREVIEW_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void previewingWithTheReadScopeIsAllowed() throws Exception {
        when(previewVariantRulesUseCase.handle(any()))
                .thenReturn(new PreviewVariantRulesUseCase.Result(
                        VariantRulesFixtures.baseline(),
                        VariantRulesFixtures.baseline().digest(),
                        new TreeMap<>()));

        mockMvc.perform(post("/api/v1/variant-rules-previews")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREVIEW_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void readingParameterSpacesAndVariantRulesNeedsTheReadScope() throws Exception {
        var write = jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write"));

        mockMvc.perform(get("/api/v1/parameter-spaces").with(write)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/parameter-spaces/1").with(write)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/experiments/{id}/variants/baseline/rules", UUID.randomUUID())
                        .with(write))
                .andExpect(status().isForbidden());
    }
}
