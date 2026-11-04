package io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.application.command.ExperimentFactoryService;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.query.GetParameterAxesQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.PreviewVariantRulesQueryHandler;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignReason;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.candidate.CandidateGenerator;
import io.github.temporalrift.workbench.search.domain.candidate.SearchSeeds;

/** The campaign view of the experiment module, over the shipped parameter spaces and the test baseline. */
class CampaignExperimentsAdapterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final CampaignExperimentsAdapter adapter = new CampaignExperimentsAdapter(
            new ExperimentFactoryService(
                    new PreviewVariantRulesQueryHandler(VariantRulesFixtures.variantRules()),
                    new GetParameterAxesQueryHandler(VariantRulesFixtures.variantRules()),
                    mock(CreateExperimentUseCase.class)),
            mock(LaneEnvironments.class),
            MAPPER,
            new CampaignExperimentsAdapter.Bounds(3600, 10));

    private static CampaignDefinition definition(String rulesDigest, int parameterSpaceVersion) {
        var baseline = MAPPER.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", rulesDigest);
        return new CampaignDefinition(
                "search",
                baseline,
                parameterSpaceVersion,
                1,
                MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("id", "random")),
                List.of(3, 4, 5),
                CampaignDefinition.DEFAULT_BUDGET_SECONDS,
                "7",
                "9",
                new SearchAlgorithm(100, 4, 2, 2));
    }

    @Test
    void aServedParameterSpaceAndBaselinePassTheCheck() {
        assertThatCode(() -> adapter.check(definition(VariantRulesFixtures.ARTIFACT_DIGEST, 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void aParameterSpaceThatIsNotServedIsRefusedOnItsVersion() {
        assertThatThrownBy(() -> adapter.check(definition(VariantRulesFixtures.ARTIFACT_DIGEST, 99)))
                .isInstanceOfSatisfying(InvalidCampaignException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidCampaignReason.UNKNOWN_PARAMETER_SPACE);
                    assertThat(e.field()).isEqualTo("parameterSpaceVersion");
                });
    }

    @Test
    void aBaselineNoLanePublishesIsRefusedOnItsDigest() {
        assertThatThrownBy(() -> adapter.check(definition("f".repeat(64), 1)))
                .isInstanceOfSatisfying(InvalidCampaignException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidCampaignReason.UNKNOWN_BASELINE);
                    assertThat(e.field()).isEqualTo("baseline.rulesArtifact.digest");
                });
    }

    @Test
    void theGridHoldsEveryOverridableParameterAtItsBaselineValue() {
        var grid = adapter.grid(definition(VariantRulesFixtures.ARTIFACT_DIGEST, 1));

        assertThat(grid.axes()).isNotEmpty();
        assertThat(grid.axes()).allSatisfy(axis -> assertThat(axis.baseline()).isBetween(axis.min(), axis.max()));
        assertThat(grid.overridesOf(grid.baselinePoint())).isEmpty();
    }

    @Test
    void aGridThatCannotBeBuiltIsAPlayRefusal() {
        assertThatThrownBy(() -> adapter.grid(definition("f".repeat(64), 1)))
                .isInstanceOf(CampaignPlayRefusedException.class);
    }

    @Test
    void validationAcceptsOverridesOnTheGridAndRejectsOnesOutsideTheRange() {
        var definition = definition(VariantRulesFixtures.ARTIFACT_DIGEST, 1);
        var validation = adapter.validation(definition);
        var axis = adapter.grid(definition).axes().getFirst();

        var outside = validation.validate(Map.of(axis.key(), axis.max().add(axis.step())));

        assertThat(outside).isEmpty();
        assertThat(validation
                        .validate(Map.<String, BigDecimal>of())
                        .orElseThrow()
                        .overrides())
                .isEmpty();
    }

    @Test
    void roundZeroOfOneHundredCandidatesAllValidatesOnTheShippedSpace() {
        var definition = definition(VariantRulesFixtures.ARTIFACT_DIGEST, 1);
        var generator = new CandidateGenerator(
                adapter.grid(definition),
                adapter.validation(definition),
                new SearchSeeds(definition.searchMasterSeed()),
                definition.algorithm());

        var candidates = generator.roundZero();

        assertThat(candidates).hasSize(100);
        assertThat(candidates.stream().map(candidate -> candidate.effectiveRulesDigest()))
                .doesNotHaveDuplicates();
    }

    @Test
    void aGameIsTheFullMatrixOfTheCampaignsPlayerCountsAndPolicies() {
        assertThat(adapter.casesPerGame(definition(VariantRulesFixtures.ARTIFACT_DIGEST, 1)))
                .isEqualTo(55);
    }
}
