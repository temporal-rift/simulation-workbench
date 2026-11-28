package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.scoring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.RescoreRunUseCase;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;

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
