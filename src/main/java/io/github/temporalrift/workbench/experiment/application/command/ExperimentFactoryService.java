package io.github.temporalrift.workbench.experiment.application.command;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.experiment.ExperimentRefusedException;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.GetParameterAxesUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.ListParameterSpacesUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;
import io.github.temporalrift.workbench.experiment.domain.IdempotencyConflictException;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** Offers the preview and freeze use cases to other modules. */
public class ExperimentFactoryService implements ExperimentFactory {

    private final PreviewVariantRulesUseCase preview;
    private final GetParameterAxesUseCase axes;
    private final CreateExperimentUseCase create;
    private final ListParameterSpacesUseCase spaces;

    public ExperimentFactoryService(
            PreviewVariantRulesUseCase preview,
            GetParameterAxesUseCase axes,
            CreateExperimentUseCase create,
            ListParameterSpacesUseCase spaces) {
        this.preview = preview;
        this.axes = axes;
        this.create = create;
        this.spaces = spaces;
    }

    @Override
    public int newestParameterSpaceVersion() {
        return spaces.handle().stream()
                .mapToInt(ParameterSpace::version)
                .max()
                .orElseThrow(() -> new IllegalStateException("no parameter space is served"));
    }

    @Override
    public List<ParameterAxis> parameterAxes(String rulesArtifactDigest, int parameterSpaceVersion) {
        try {
            return axes.handle(rulesArtifactDigest, parameterSpaceVersion).stream()
                    .map(ExperimentFactoryService::axis)
                    .toList();
        } catch (ExperimentValidationException e) {
            throw new ExperimentRefusedException(
                    e.getMessage(), e.reason().map(Enum::name).orElse(null), e);
        }
    }

    private static ParameterAxis axis(VariantRules.Axis axis) {
        return switch (axis) {
            case VariantRules.NumberAxis(var key, var min, var max, var step, var baseline) ->
                new NumberAxis(key, min, max, step, baseline);
            case VariantRules.BooleanAxis(var key, var baseline) -> new BooleanAxis(key, baseline);
        };
    }

    @Override
    public VariantPreview previewVariant(
            String rulesArtifactDigest, int parameterSpaceVersion, Map<String, ParameterValue> overrides) {
        try {
            var result = preview.handle(
                    new PreviewVariantRulesUseCase.Query(rulesArtifactDigest, parameterSpaceVersion, overrides));
            return new VariantPreview(result.effectiveRulesDigest(), result.overrides());
        } catch (ExperimentValidationException e) {
            throw new ExperimentRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public UUID freeze(UUID idempotencyKey, JsonNode manifest) {
        try {
            return create.handle(new CreateExperimentUseCase.Command(idempotencyKey, manifest))
                    .experimentId();
        } catch (ExperimentValidationException | IdempotencyConflictException e) {
            throw new ExperimentRefusedException(e.getMessage(), e);
        }
    }
}
