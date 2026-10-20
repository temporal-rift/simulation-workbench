package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.DerivedParameter;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EffectiveRulesPropertiesValue;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.HeldFixedParameter;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterConstraint;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterDefinition;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.VariantRulesPreview;

/** Maps parameter spaces and variant rules onto the published contract. */
final class VariantRulesMapper {

    private VariantRulesMapper() {}

    static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterSpace space(
            ParameterSpace space) {
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterSpace(
                space.version(),
                space.parameters().stream()
                        .map(parameter -> new ParameterDefinition(
                                parameter.key(),
                                parameter.services(),
                                parameter.baseline(),
                                parameter.min(),
                                parameter.max(),
                                parameter.step()))
                        .toList(),
                space.derived().stream()
                        .map(derived -> new DerivedParameter(derived.key(), derived.from(), derived.factor()))
                        .toList(),
                space.constraints().stream()
                        .map(constraint -> new ParameterConstraint(
                                constraint.id(), constraint.expression().source(), constraint.description()))
                        .toList(),
                space.heldFixed().stream()
                        .map(fixed -> new HeldFixedParameter(fixed.key(), fixed.reason()))
                        .toList());
    }

    static VariantRulesPreview preview(PreviewVariantRulesUseCase.Result result) {
        return new VariantRulesPreview(
                rules(result.rules()), result.effectiveRulesDigest(), new LinkedHashMap<>(result.overrides()));
    }

    static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EffectiveRules rules(
            EffectiveRules rules) {
        Map<String, EffectiveRulesPropertiesValue> properties = new LinkedHashMap<>();
        rules.properties().forEach((key, value) -> properties.put(key, new RulesValue(value)));
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EffectiveRules(
                io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EffectiveRules
                        .SchemaVersionEnum.NUMBER_1,
                properties);
    }

    static Map<String, BigDecimal> overrides(Map<String, BigDecimal> overrides) {
        return overrides == null ? Map.of() : overrides;
    }

    /** A rules value as the JSON number, boolean, string or array it is; the generated union names none of them. */
    private record RulesValue(@JsonValue JsonNode value) implements EffectiveRulesPropertiesValue {}
}
