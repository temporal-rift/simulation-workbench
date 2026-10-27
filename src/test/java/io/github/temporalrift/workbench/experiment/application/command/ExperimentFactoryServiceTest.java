package io.github.temporalrift.workbench.experiment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.ExperimentRefusedException;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.query.PreviewVariantRulesQueryHandler;
import io.github.temporalrift.workbench.experiment.domain.ExperimentErrorCode;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;
import io.github.temporalrift.workbench.experiment.domain.IdempotencyConflictException;

class ExperimentFactoryServiceTest {

    private final CreateExperimentUseCase create = mock(CreateExperimentUseCase.class);
    private final ExperimentFactoryService factory = new ExperimentFactoryService(
            new PreviewVariantRulesQueryHandler(VariantRulesFixtures.variantRules()), create);

    @Test
    void aVariantPreviewNamesItsDigestAndTheOverridesFreezingWouldStore() {
        var baseline = factory.previewVariant(VariantRulesFixtures.ARTIFACT_DIGEST, 1, Map.of());
        var changed = factory.previewVariant(
                VariantRulesFixtures.ARTIFACT_DIGEST,
                1,
                Map.of("game.rules.win-score-threshold", BigDecimal.valueOf(22)));

        assertThat(baseline.overrides()).isEmpty();
        assertThat(changed.overrides()).containsOnlyKeys("game.rules.win-score-threshold");
        assertThat(changed.effectiveRulesDigest()).isNotEqualTo(baseline.effectiveRulesDigest());
    }

    @Test
    void anOverrideOutsideTheParameterSpaceIsRefused() {
        assertThatThrownBy(() -> factory.previewVariant(
                        VariantRulesFixtures.ARTIFACT_DIGEST,
                        1,
                        Map.of("game.rules.win-score-threshold", BigDecimal.TEN)))
                .isInstanceOf(ExperimentRefusedException.class);
    }

    @Test
    void freezingReturnsTheExperimentTheUseCaseCreated() {
        var experiment = UUID.randomUUID();
        var manifest = new ObjectMapper().createObjectNode();
        when(create.handle(any()))
                .thenReturn(new CreateExperimentUseCase.Result(experiment, manifest, "d".repeat(64), "now"));

        assertThat(factory.freeze(UUID.randomUUID(), manifest)).isEqualTo(experiment);
    }

    @Test
    void aRefusedManifestOrAReusedKeyIsRefused() {
        var manifest = new ObjectMapper().createObjectNode();
        when(create.handle(any()))
                .thenThrow(new ExperimentValidationException(ExperimentErrorCode.INVALID_EXPERIMENT, "no"))
                .thenThrow(new IdempotencyConflictException("reused"));

        assertThatThrownBy(() -> factory.freeze(UUID.randomUUID(), manifest))
                .isInstanceOf(ExperimentRefusedException.class)
                .hasMessage("no");
        assertThatThrownBy(() -> factory.freeze(UUID.randomUUID(), manifest))
                .isInstanceOf(ExperimentRefusedException.class)
                .hasMessage("reused");
    }
}
