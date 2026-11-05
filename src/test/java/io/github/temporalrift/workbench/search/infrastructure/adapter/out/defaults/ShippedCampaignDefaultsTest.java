package io.github.temporalrift.workbench.search.infrastructure.adapter.out.defaults;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.analysis.CandidateScreening;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;

/** The defaults over the search budget records the service ships. */
class ShippedCampaignDefaultsTest {

    private final ExperimentFactory experiments = mock(ExperimentFactory.class);
    private final CandidateScreening screening = mock(CandidateScreening.class);
    private final ShippedCampaignDefaults defaults = new ShippedCampaignDefaults(
            experiments, screening, JsonMapper.builder().build());

    @Test
    void theAlgorithmIsTheNewestApprovedSearchBudgetRecord() {
        assertThat(defaults.algorithm()).isEqualTo(new SearchAlgorithm(32, 2, 2, 1));
    }

    @Test
    void theVersionsAreTheNewestTheOtherModulesServe() {
        when(experiments.newestParameterSpaceVersion()).thenReturn(3);
        when(screening.newestCriterionVersion()).thenReturn(5);

        assertThat(defaults.parameterSpaceVersion()).isEqualTo(3);
        assertThat(defaults.criterionVersion()).isEqualTo(5);
    }
}
