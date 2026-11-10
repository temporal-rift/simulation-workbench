package io.github.temporalrift.workbench.search.infrastructure.adapter.out.defaults;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
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
        assertThat(defaults.algorithm())
                .isEqualTo(new SearchAlgorithm(SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2, 32, 2, 2, 1));
    }

    @Test
    void theNewestOfSeveralRecordsWins() {
        var older = record("{\"version\":1,\"algorithm\":" + algorithm("SUCCESSIVE_HALVING_V1", 4) + "}");
        var newer = record("{\"version\":3,\"algorithm\":" + algorithm("SUCCESSIVE_HALVING_V2", 16) + "}");

        assertThat(over(newer, older).algorithm())
                .isEqualTo(new SearchAlgorithm(SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2, 16, 2, 2, 1));
    }

    @Test
    void noRecordIsRefused() {
        assertThatThrownBy(() -> over())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No search budget record");
    }

    @Test
    void aVersionConfiguredTwiceIsRefused() {
        var first = record("{\"version\":2,\"algorithm\":" + algorithm("SUCCESSIVE_HALVING_V1", 4) + "}");
        var second = record("{\"version\":2,\"algorithm\":" + algorithm("SUCCESSIVE_HALVING_V1", 8) + "}");

        assertThatThrownBy(() -> over(first, second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured twice");
    }

    @Test
    void anAlgorithmTheServiceDoesNotImplementIsRefusedNamingTheRecord() {
        var unknown = record("{\"version\":1,\"algorithm\":" + algorithm("SUCCESSIVE_HALVING_V9", 4) + "}");

        assertThatThrownBy(() -> over(unknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v1.json")
                .hasMessageContaining("unknown algorithm");
    }

    @Test
    void aRecordWithoutAnIntegerVersionIsRefused() {
        var untyped = record("{\"version\":\"one\",\"algorithm\":" + algorithm("SUCCESSIVE_HALVING_V1", 4) + "}");

        assertThatThrownBy(() -> over(untyped))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no integer version");
    }

    @Test
    void aRecordWithoutAnIntegerFigureIsRefusedNamingIt() {
        var missing =
                record("{\"version\":1,\"algorithm\":{\"version\":\"SUCCESSIVE_HALVING_V1\",\"initialCandidates\":4,"
                        + "\"screeningSeeds\":2,\"eta\":\"two\",\"shortListSize\":1}}");

        assertThatThrownBy(() -> over(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no integer eta");
    }

    @Test
    void aRecordWhoseFiguresTheAlgorithmRefusesIsInvalid() {
        var invalid =
                record("{\"version\":1,\"algorithm\":{\"version\":\"SUCCESSIVE_HALVING_V1\",\"initialCandidates\":1,"
                        + "\"screeningSeeds\":2,\"eta\":2,\"shortListSize\":1}}");

        assertThatThrownBy(() -> over(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is invalid");
    }

    @Test
    void theVersionsAreTheNewestTheOtherModulesServe() {
        when(experiments.newestParameterSpaceVersion()).thenReturn(3);
        when(screening.newestCriterionVersion()).thenReturn(5);

        assertThat(defaults.parameterSpaceVersion()).isEqualTo(3);
        assertThat(defaults.criterionVersion()).isEqualTo(5);
    }

    private ShippedCampaignDefaults over(Resource... records) {
        return new ShippedCampaignDefaults(
                experiments, screening, JsonMapper.builder().build(), records);
    }

    private static String algorithm(String version, int initialCandidates) {
        return "{\"version\":\"" + version + "\",\"initialCandidates\":" + initialCandidates
                + ",\"screeningSeeds\":2,\"eta\":2,\"shortListSize\":1}";
    }

    private static Resource record(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "v1.json";
            }
        };
    }
}
