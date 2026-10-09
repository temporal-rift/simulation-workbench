package io.github.temporalrift.workbench.execution.infrastructure.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Attempt;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CaseResult;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CaseSummary;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Failure;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ModelCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Run;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Winner;

/**
 * Every field of the run and case representations is required by the published contract, including the
 * nullable ones (a run's {@code startedAt}, {@code finishedAt} and {@code failure}, a case's {@code result}, a
 * winner's {@code winType}), so the service-wide non-null inclusion must not drop them from these responses.
 */
@Configuration
class RunApiJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer runApiKeepsNullableFields() {
        var always = JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS);
        return builder -> List.of(
                        Run.class,
                        Failure.class,
                        ModelCase.class,
                        CaseSummary.class,
                        Attempt.class,
                        CaseResult.class,
                        Winner.class)
                .forEach(type -> builder.withConfigOverride(type, override -> override.setInclude(always)));
    }
}
