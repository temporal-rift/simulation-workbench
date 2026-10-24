package io.github.temporalrift.workbench.experiment.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

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
    void aPreviewValidatesTheManifestOnce() {
        var manifest = spy((ObjectNode) ExperimentManifests.valid());

        handler.handle(manifest);

        verify(manifest, times(1)).has("schemaVersion");
    }

    @Test
    void nullManifestFollowsInvalidExperimentPath() {
        assertThatThrownBy(() -> handler.handle(null))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }
}
