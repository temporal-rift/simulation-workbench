package io.github.temporalrift.workbench.experiment.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.domain.ExperimentErrorCode;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;

class PreviewMatrixQueryHandlerTest {

    private final PreviewMatrixQueryHandler handler = new PreviewMatrixQueryHandler();

    @Test
    void validManifestIsPreviewed() {
        var cases = handler.handle(ExperimentManifests.singleSeedSinglePolicySingleVariant());

        assertThat(cases).hasSize(55);
    }

    @Test
    void nullManifestFollowsInvalidExperimentPath() {
        assertThatThrownBy(() -> handler.handle(null))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }
}
