package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.scoring;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.regex.Pattern;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.RescoreRequest;

/** Validates indexed values before the generated set can discard duplicate entries. */
@ControllerAdvice(assignableTypes = ScoringModelController.class)
class RescoreSweepBodyAdvice extends RequestBodyAdviceAdapter {
    private static final Pattern PATH =
            Pattern.compile("^(reasonPoints\\..{1,64}|escalations\\.[a-z][a-z0-9-]{0,31}\\.(base|increment))$");
    private final ObjectMapper mapper;

    RescoreSweepBodyAdvice(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(
            MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == RescoreRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage input,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] bytes = input.getBody().readAllBytes();
        tools.jackson.databind.JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (JacksonException ex) {
            throw new InvalidScoringModelException("body", "The request body is malformed");
        }
        var sweep = root == null ? null : root.get("sweep");
        if (sweep != null) {
            if (!sweep.isArray() || sweep.isEmpty() || sweep.size() > 24) {
                throw new InvalidScoringModelException("sweep", "sweep must contain between 1 and 24 parameters");
            }
            var seen = new HashSet<String>();
            for (int i = 0; i < sweep.size(); i++) {
                var item = sweep.get(i);
                if (!item.isTextual() || !PATH.matcher(item.asString()).matches() || !seen.add(item.asString())) {
                    throw new InvalidScoringModelException("sweep[" + i + "]", "invalid or repeated parameter");
                }
            }
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }
}
