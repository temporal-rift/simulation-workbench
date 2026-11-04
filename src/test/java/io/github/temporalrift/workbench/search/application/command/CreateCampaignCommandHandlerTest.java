package io.github.temporalrift.workbench.search.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.application.port.in.CreateCampaignUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignReason;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;

class CreateCampaignCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryCampaigns campaigns = new InMemoryCampaigns();
    private final CampaignExperiments experiments = mock(CampaignExperiments.class);
    private final CandidateEvaluation evaluation = mock(CandidateEvaluation.class);
    private final CreateCampaignCommandHandler create =
            new CreateCampaignCommandHandler(campaigns, experiments, evaluation, Clock.fixed(NOW, ZoneOffset.UTC));

    CreateCampaignCommandHandlerTest() {
        when(evaluation.servesCriterion(1)).thenReturn(true);
    }

    @Test
    void aValidDefinitionIsQueuedWithTheDefaultBudgetAndParallelism() {
        var campaign = create.handle(command(UUID.randomUUID(), "7", "9"));

        assertThat(campaign.state()).isEqualTo(CampaignState.QUEUED);
        assertThat(campaign.definition().budgetSeconds()).isEqualTo(CampaignDefinition.DEFAULT_BUDGET_SECONDS);
        assertThat(campaign.parallelism()).isEqualTo(CampaignDefinition.DEFAULT_PARALLELISM);
        assertThat(campaign.createdAt()).isEqualTo(NOW);
        assertThat(campaigns.find(campaign.campaignId())).isPresent();
        verify(experiments).check(campaign.definition());
    }

    @Test
    void theSameKeyAndDefinitionReturnTheOriginalCampaignWithoutStoringAnother() {
        var key = UUID.randomUUID();

        var first = create.handle(command(key, "7", "9"));
        var repeated = create.handle(command(key, "7", "9"));

        assertThat(repeated).isEqualTo(first);
        assertThat(campaigns.list(null, 10, 0).total()).isOne();
    }

    @Test
    void aKeyReusedWithAnotherDefinitionIsAConflict() {
        var key = UUID.randomUUID();
        create.handle(command(key, "7", "9"));

        assertThatThrownBy(() -> create.handle(command(key, "7", "10")))
                .isInstanceOf(CampaignIdempotencyConflictException.class);
    }

    @Test
    void equalMasterSeedsAreRefusedOnTheConfirmationSeed() {
        assertThatThrownBy(() -> create.handle(command(UUID.randomUUID(), "7", "7")))
                .isInstanceOfSatisfying(InvalidCampaignException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidCampaignReason.EQUAL_MASTER_SEEDS);
                    assertThat(e.field()).isEqualTo("confirmationMasterSeed");
                });
        assertThat(campaigns.list(null, 10, 0).total()).isZero();
    }

    @Test
    void aBaselineOrParameterSpaceThatIsNotServedIsRefusedWithTheReasonTheExperimentsGave() {
        doThrow(new InvalidCampaignException(
                        InvalidCampaignReason.UNKNOWN_BASELINE, "baseline.rulesArtifact.digest", "no"))
                .when(experiments)
                .check(any());

        assertThatThrownBy(() -> create.handle(command(UUID.randomUUID(), "7", "9")))
                .isInstanceOfSatisfying(InvalidCampaignException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidCampaignReason.UNKNOWN_BASELINE);
                    assertThat(e.field()).isEqualTo("baseline.rulesArtifact.digest");
                });
    }

    @Test
    void aCriterionThatIsNotServedIsRefused() {
        when(evaluation.servesCriterion(1)).thenReturn(false);

        assertThatThrownBy(() -> create.handle(command(UUID.randomUUID(), "7", "9")))
                .isInstanceOfSatisfying(InvalidCampaignException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidCampaignReason.UNKNOWN_CRITERION);
                    assertThat(e.field()).isEqualTo("criterionVersion");
                });
    }

    @Test
    void aValueTheDefinitionRefusesIsInvalid() {
        var blank = new CreateCampaignUseCase.Command(
                UUID.randomUUID(),
                " ",
                baseline(),
                1,
                1,
                policies(),
                List.of(3),
                null,
                null,
                "7",
                "9",
                new SearchAlgorithm(8, 2, 2, 2));

        assertThatThrownBy(() -> create.handle(blank))
                .isInstanceOfSatisfying(
                        InvalidCampaignException.class,
                        e -> assertThat(e.reason()).isEqualTo(InvalidCampaignReason.INVALID_VALUE));
    }

    private static CreateCampaignUseCase.Command command(UUID key, String searchSeed, String confirmationSeed) {
        return new CreateCampaignUseCase.Command(
                key,
                "search",
                baseline(),
                1,
                1,
                policies(),
                List.of(3, 4),
                null,
                null,
                searchSeed,
                confirmationSeed,
                new SearchAlgorithm(8, 2, 2, 2));
    }

    private static tools.jackson.databind.JsonNode baseline() {
        var node = JsonMapper.builder().build().createObjectNode();
        node.putObject("rulesArtifact").put("digest", "d".repeat(64));
        return node;
    }

    private static tools.jackson.databind.JsonNode policies() {
        var mapper = JsonMapper.builder().build();
        return mapper.createArrayNode().add(mapper.createObjectNode().put("id", "random"));
    }
}
