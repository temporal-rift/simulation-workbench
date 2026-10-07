package io.github.temporalrift.workbench.experiment.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.domain.ExperimentErrorCode;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy.PolicyReferenceVerifierAdapter;
import io.github.temporalrift.workbench.policy.application.query.BaselinePolicyCatalog;

class PreviewMatrixQueryHandlerTest {

    private final PreviewMatrixQueryHandler handler =
            new PreviewMatrixQueryHandler(new PolicyReferenceVerifierAdapter(new BaselinePolicyCatalog()));

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
