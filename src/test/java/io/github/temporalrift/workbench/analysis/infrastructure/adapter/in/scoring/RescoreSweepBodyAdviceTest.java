package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.RescoreRunUseCase;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.RescoringResult;

class RescoreSweepBodyAdviceTest {
    private final RescoreSweepBodyAdvice advice = new RescoreSweepBodyAdvice(new ObjectMapper());

    @Test
    void rejectsDuplicateAtOriginalIndexBeforeDeserialization() {
        var body = "{\"sweep\":[\"reasonPoints.A\",\"reasonPoints.A\"]}";
        assertThatThrownBy(() -> advice.beforeBodyRead(input(body), null, null, null))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.field())
                                .isEqualTo("sweep[1]"));
    }

    @Test
    void rejectsMalformedPathAtOriginalIndex() {
        var body = "{\"sweep\":[\"reasonPoints.A\",\"maxEras\"]}";
        assertThatThrownBy(() -> advice.beforeBodyRead(input(body), null, null, null))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.field())
                                .isEqualTo("sweep[1]"));
    }

    @Test
    void duplicateIsReturnedAsAnIndexedHttpProblem() throws Exception {
        var controller =
                new ScoringModelController(mock(EvaluateScoringModelUseCase.class), mock(RescoreRunUseCase.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice, new ScoringModelExceptionHandler())
                .build();
        mvc.perform(post("/api/v1/runs/00000000-0000-0000-0000-0000000000aa/rescorings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sweep\":[\"reasonPoints.A\",\"reasonPoints.A\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("sweep[1]"));
    }

    @Test
    void keepsSubmittedOrderWhenMappingTheHttpRequest() throws Exception {
        var rescore = mock(RescoreRunUseCase.class);
        when(rescore.handle(any())).thenAnswer(invocation -> {
            RescoreRunUseCase.Query query = invocation.getArgument(0);
            assertThat(query.sweep()).containsExactly("reasonPoints.A", "reasonPoints.UNKNOWN");
            throw new InvalidScoringModelException("sweep[1]", "unknown parameter");
        });
        var controller = new ScoringModelController(mock(EvaluateScoringModelUseCase.class), rescore);
        var mapper = projectMapper();
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new RescoreSweepBodyAdvice(mapper), new ScoringModelExceptionHandler())
                .build();
        mvc.perform(post("/api/v1/runs/00000000-0000-0000-0000-0000000000aa/rescorings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(",\"sweep\":[\"reasonPoints.A\",\"reasonPoints.UNKNOWN\"]")))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(((InvalidScoringModelException) result.getResolvedException()).field())
                        .isEqualTo("sweep[1]"));
    }

    @Test
    void omitsSensitivityFromBaseOnlyHttpResponse() throws Exception {
        var rescore = mock(RescoreRunUseCase.class);
        when(rescore.handle(any())).thenReturn(new RescoringResult(true, List.of(), List.of()));
        var mapper = projectMapper();
        var mvc = MockMvcBuilders.standaloneSetup(
                        new ScoringModelController(mock(EvaluateScoringModelUseCase.class), rescore))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setControllerAdvice(new RescoreSweepBodyAdvice(mapper), new ScoringModelExceptionHandler())
                .build();
        mvc.perform(post("/api/v1/runs/00000000-0000-0000-0000-0000000000aa/rescorings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.sensitivity").doesNotHaveJsonPath());
    }

    private String request(String extra) {
        return "{\"engine\":{\"maxEras\":5,\"reasonPoints\":[{\"scoreReason\":\"A\",\"points\":1}]},"
                + "\"variantLabel\":\"v\",\"tableId\":\"t\"" + extra + "}";
    }

    private JsonMapper projectMapper() {
        return JsonMapper.builder()
                .addModule(new SimpleModule().addAbstractTypeMapping(Set.class, LinkedHashSet.class))
                .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }

    private HttpInputMessage input(String body) {
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
    }
}
