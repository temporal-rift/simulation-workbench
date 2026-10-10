package io.github.temporalrift.workbench.experiment.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewExperimentUseCase;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy.PolicyReferenceVerifierAdapter;
import io.github.temporalrift.workbench.policy.application.query.BaselinePolicyCatalog;

class PreviewExperimentQueryHandlerTest {

    private final PreviewExperimentQueryHandler handler =
            new PreviewExperimentQueryHandler(new PolicyReferenceVerifierAdapter(new BaselinePolicyCatalog()));

    @Test
    void theDigestIsTheOneFreezingWouldAssign() {
        var manifest = ExperimentManifests.valid();

        var preview = handler.handle(new PreviewExperimentUseCase.Query(manifest, 10, 0));

        assertThat(preview.manifestDigest()).isEqualTo(ManifestDigest.sha256Hex(manifest));
        assertThat(preview.caseCount()).isEqualTo(220);
    }

    @Test
    void theBreakdownCountsEveryVariantAndPlayerCountOnceInVariantOrder() {
        var preview = handler.handle(new PreviewExperimentUseCase.Query(ExperimentManifests.valid(), 1, 0));

        assertThat(preview.breakdown())
                .extracting(
                        PreviewExperimentUseCase.Breakdown::variantLabel,
                        PreviewExperimentUseCase.Breakdown::playerCount,
                        PreviewExperimentUseCase.Breakdown::caseCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("baseline", 3, 60),
                        org.assertj.core.groups.Tuple.tuple("baseline", 4, 40),
                        org.assertj.core.groups.Tuple.tuple("baseline", 5, 10),
                        org.assertj.core.groups.Tuple.tuple("candidate", 3, 60),
                        org.assertj.core.groups.Tuple.tuple("candidate", 4, 40),
                        org.assertj.core.groups.Tuple.tuple("candidate", 5, 10));
    }

    @Test
    void aPageIsAWindowOfTheStableOrder() {
        var manifest = ExperimentManifests.valid();
        var all = handler.handle(new PreviewExperimentUseCase.Query(manifest, 500, 0))
                .cases();

        var page = handler.handle(new PreviewExperimentUseCase.Query(manifest, 7, 30));

        assertThat(page.cases()).containsExactlyElementsOf(all.subList(30, 37));
        assertThat(page.limit()).isEqualTo(7);
        assertThat(page.offset()).isEqualTo(30);
    }

    @Test
    void aPageStartingBeyondTheMatrixIsEmptyAndKeepsTheTotal() {
        var page = handler.handle(new PreviewExperimentUseCase.Query(ExperimentManifests.valid(), 10, 5000));

        assertThat(page.cases()).isEmpty();
        assertThat(page.caseCount()).isEqualTo(220);
    }
}
