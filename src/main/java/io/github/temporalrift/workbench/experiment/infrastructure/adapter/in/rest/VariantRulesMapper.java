package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentErrorCode;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRulesRefusal;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.OverrideValues;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.BooleanParameterDefinition;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.DerivedParameter;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EffectiveRulesPropertiesValue;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.HeldFixedParameter;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.NumberParameterDefinition;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.OverrideValue;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterConstraint;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterDefinition;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterType;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.VariantRulesPreview;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** Maps parameter spaces and variant rules onto the published contract. */
final class VariantRulesMapper {

    private VariantRulesMapper() {}

    static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterSpace space(
            ParameterSpace space) {
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterSpace(
                space.version(),
                space.parameters().stream().map(VariantRulesMapper::definition).toList(),
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

    private static ParameterDefinition definition(ParameterSpace.Parameter parameter) {
        return switch (parameter) {
            case ParameterSpace.NumberParameter(var key, var services, var baseline, var min, var max, var step) ->
                new NumberParameterDefinition(ParameterType.NUMBER, key, services, baseline, min, max, step);
            case ParameterSpace.BooleanParameter(var key, var services, var baseline) ->
                new BooleanParameterDefinition(ParameterType.BOOLEAN, key, services, baseline);
        };
    }

    static VariantRulesPreview preview(PreviewVariantRulesUseCase.Result result) {
        return new VariantRulesPreview(
                rules(result.rules()), result.effectiveRulesDigest(), OverrideValues.of(result.overrides()));
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

    /**
     * The overrides a preview request sets; empty when it sets none.
     *
     * @throws io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException {@code WRONG_TYPE} for a
     *     value that is neither a number nor a boolean
     */
    static Map<String, ParameterValue> overrides(Map<String, OverrideValue> overrides) {
        var variant = JsonNodeFactory.instance.objectNode();
        variant.set("overrides", OverrideValues.json(overrides));
        try {
            return VariantRules.overridesOf(variant);
        } catch (VariantRulesRefusal refusal) {
            throw refusal.at(ExperimentErrorCode.INVALID_REQUEST, "");
        }
    }

    /** A rules value as the JSON number, boolean, string or array it is; the generated union names none of them. */
    private record RulesValue(@JsonValue JsonNode value) implements EffectiveRulesPropertiesValue {}
}
