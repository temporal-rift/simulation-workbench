package io.github.temporalrift.workbench.experiment.application.command;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.experiment.ExperimentRefusedException;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;
import io.github.temporalrift.workbench.experiment.domain.IdempotencyConflictException;

/** Offers the preview and freeze use cases to other modules. */
public class ExperimentFactoryService implements ExperimentFactory {

    private final PreviewVariantRulesUseCase preview;
    private final CreateExperimentUseCase create;

    public ExperimentFactoryService(PreviewVariantRulesUseCase preview, CreateExperimentUseCase create) {
        this.preview = preview;
        this.create = create;
    }

    @Override
    public VariantPreview previewVariant(
            String rulesArtifactDigest, int parameterSpaceVersion, Map<String, BigDecimal> overrides) {
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
