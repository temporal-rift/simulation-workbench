package io.github.temporalrift.workbench.fidelity.infrastructure.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCheck;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SimulatorFidelity;

/**
 * Every field of the fidelity representations is required by the published contract, including the nullable ones (a
 * check's {@code verdict}, {@code lane}, {@code failure} and timestamps, a case's {@code firstDivergence} and
 * {@code failure}, and the gate's {@code record}), so the service-wide non-null inclusion must not drop them.
 */
@Configuration
public class FidelityApiJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer fidelityApiKeepsNullableFields() {
        var always = JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS);
        return builder -> List.of(FidelityCheck.class, FidelityCase.class, SimulatorFidelity.class)
                .forEach(type -> builder.withConfigOverride(type, override -> override.setInclude(always)));
    }
}
