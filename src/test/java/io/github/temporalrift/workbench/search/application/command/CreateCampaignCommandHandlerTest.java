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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.application.port.in.CreateCampaignUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCommandKey;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignReason;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignDefaults;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;
import io.github.temporalrift.workbench.search.domain.port.out.MasterSeeds;

class CreateCampaignCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryCampaigns campaigns = new InMemoryCampaigns();
    private final CampaignExperiments experiments = mock(CampaignExperiments.class);
    private final CandidateEvaluation evaluation = mock(CandidateEvaluation.class);
    private final CampaignDefaults defaults = mock(CampaignDefaults.class);
    private final Deque<String> drawn = new ArrayDeque<>();
    private final MasterSeeds seeds = drawn::removeFirst;
    private final CreateCampaignCommandHandler create = new CreateCampaignCommandHandler(
            campaigns, experiments, evaluation, defaults, seeds, Clock.fixed(NOW, ZoneOffset.UTC));

    CreateCampaignCommandHandlerTest() {
        when(evaluation.servesCriterion(1)).thenReturn(true);
        when(defaults.algorithm()).thenReturn(new SearchAlgorithm(32, 2, 2, 1));
        when(defaults.parameterSpaceVersion()).thenReturn(1);
        when(defaults.criterionVersion()).thenReturn(1);
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

        var another = command(key, "7", "10");

        assertThatThrownBy(() -> create.handle(another)).isInstanceOf(CampaignIdempotencyConflictException.class);
    }

    @Test
    void aKeyACommandOnACampaignClaimedIsAConflictAndCreatesNothing() {
        var key = UUID.randomUUID();
        var other = create.handle(command(UUID.randomUUID(), "7", "9"));
        var stored = campaigns.find(other.campaignId()).orElseThrow();
        campaigns.apply(CampaignChange.of(stored, other.pause(), NOW)
                .claiming(CampaignCommandKey.of(key, "pauseCampaign", other.campaignId(), "")));

        var request = command(key, "7", "9");

        assertThatThrownBy(() -> create.handle(request)).isInstanceOf(CampaignIdempotencyConflictException.class);
        assertThat(campaigns.list(null, 10, 0).total()).isOne();
    }

    @Test
    void aMinimalDefinitionIsCompletedWithTheRecordsAndTwoDrawnSeeds() {
        drawn.addAll(List.of("11", "22"));

        var campaign = create.handle(minimal(UUID.randomUUID()));

        var definition = campaign.definition();
        assertThat(definition.algorithm()).isEqualTo(new SearchAlgorithm(32, 2, 2, 1));
        assertThat(definition.parameterSpaceVersion()).isOne();
        assertThat(definition.criterionVersion()).isOne();
        assertThat(definition.searchMasterSeed()).isEqualTo("11");
        assertThat(definition.confirmationMasterSeed()).isEqualTo("22");
    }

    @Test
    void theNewestParameterSpaceVersionIsUsedWhenTheDefinitionNamesNone() {
        when(defaults.parameterSpaceVersion()).thenReturn(4);
        drawn.addAll(List.of("11", "22"));

        var campaign = create.handle(minimal(UUID.randomUUID()));

        assertThat(campaign.definition().parameterSpaceVersion()).isEqualTo(4);
    }

    @Test
    void valuesTheDefinitionSuppliesAreKeptAndNothingIsDrawn() {
        var campaign = create.handle(command(UUID.randomUUID(), "7", "9"));

        var definition = campaign.definition();
        assertThat(definition.searchMasterSeed()).isEqualTo("7");
        assertThat(definition.confirmationMasterSeed()).isEqualTo("9");
        assertThat(definition.algorithm()).isEqualTo(new SearchAlgorithm(8, 2, 2, 2));
    }

    @Test
    void aPartialAlgorithmTakesTheRecordsValueForEveryFieldItLeavesOut() {
        drawn.addAll(List.of("11", "22"));
        var partial =
                minimalWith(UUID.randomUUID(), new CreateCampaignUseCase.AlgorithmRequest(null, 10, null, null, null));

        var campaign = create.handle(partial);

        assertThat(campaign.definition().algorithm()).isEqualTo(new SearchAlgorithm(10, 2, 2, 1));
    }

    @Test
    void aMergedAlgorithmThatIsInvalidIsRefusedOnTheAlgorithm() {
        drawn.addAll(List.of("11", "22"));
        var invalid =
                minimalWith(UUID.randomUUID(), new CreateCampaignUseCase.AlgorithmRequest(null, null, null, 1, null));

        assertThatThrownBy(() -> create.handle(invalid)).isInstanceOfSatisfying(InvalidCampaignException.class, e -> {
            assertThat(e.reason()).isEqualTo(InvalidCampaignReason.INVALID_VALUE);
            assertThat(e.field()).isEqualTo("algorithm");
        });
    }

    @Test
    void anAlgorithmTheServiceDoesNotImplementIsRefusedOnItsVersion() {
        var unknown = minimalWith(
                UUID.randomUUID(),
                new CreateCampaignUseCase.AlgorithmRequest("SUCCESSIVE_HALVING_V2", null, null, null, null));

        assertThatThrownBy(() -> create.handle(unknown)).isInstanceOfSatisfying(InvalidCampaignException.class, e -> {
            assertThat(e.reason()).isEqualTo(InvalidCampaignReason.INVALID_VALUE);
            assertThat(e.field()).isEqualTo("algorithm.version");
        });
        assertThat(campaigns.list(null, 10, 0).total()).isZero();
    }

    @Test
    void theAlgorithmVersionTheRequestNamesIsPartOfItsIdentity() {
        var key = UUID.randomUUID();
        drawn.addAll(List.of("11", "22"));
        create.handle(minimalWith(key, new CreateCampaignUseCase.AlgorithmRequest(null, 10, null, null, null)));

        var named = minimalWith(
                key, new CreateCampaignUseCase.AlgorithmRequest("SUCCESSIVE_HALVING_V1", 10, null, null, null));

        assertThatThrownBy(() -> create.handle(named)).isInstanceOf(CampaignIdempotencyConflictException.class);
    }

    @Test
    void aGeneratedSeedDiffersFromTheSeedTheDefinitionSupplies() {
        drawn.addAll(List.of("7", "31"));
        var onlySearch = new CreateCampaignUseCase.Command(
                UUID.randomUUID(),
                "search",
                baseline(),
                null,
                null,
                policies(),
                List.of(3),
                null,
                null,
                "7",
                null,
                null);

        var campaign = create.handle(onlySearch);

        assertThat(campaign.definition().searchMasterSeed()).isEqualTo("7");
        assertThat(campaign.definition().confirmationMasterSeed()).isEqualTo("31");
    }

    @Test
    void aDrawnSeedThatEqualsTheOtherOneIsDrawnAgain() {
        drawn.addAll(List.of("11", "11", "22"));

        var campaign = create.handle(minimal(UUID.randomUUID()));

        assertThat(campaign.definition().searchMasterSeed()).isEqualTo("11");
        assertThat(campaign.definition().confirmationMasterSeed()).isEqualTo("22");
    }

    @Test
    void aRetryOfAMinimalDefinitionReturnsTheSameSeedsAndDrawsNothingElse() {
        var key = UUID.randomUUID();
        drawn.addAll(List.of("11", "22"));
        var first = create.handle(minimal(key));

        var repeated = create.handle(minimal(key));

        assertThat(repeated).isEqualTo(first);
        assertThat(repeated.definition().searchMasterSeed()).isEqualTo("11");
        assertThat(drawn).isEmpty();
        assertThat(campaigns.list(null, 10, 0).total()).isOne();
    }

    @Test
    void aMinimalDefinitionIsNotTheSameRequestAsOneThatNamesTheValuesItResolvedTo() {
        var key = UUID.randomUUID();
        drawn.addAll(List.of("11", "22"));
        create.handle(minimal(key));

        var explicit = command(key, "11", "22");

        assertThatThrownBy(() -> create.handle(explicit)).isInstanceOf(CampaignIdempotencyConflictException.class);
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
                new CreateCampaignUseCase.AlgorithmRequest(null, 8, 2, 2, 2));

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
                new CreateCampaignUseCase.AlgorithmRequest(null, 8, 2, 2, 2));
    }

    private static CreateCampaignUseCase.Command minimal(UUID key) {
        return minimalWith(key, null);
    }

    private static CreateCampaignUseCase.Command minimalWith(
            UUID key, CreateCampaignUseCase.AlgorithmRequest algorithm) {
        return new CreateCampaignUseCase.Command(
                key, "search", baseline(), null, null, policies(), List.of(3), null, null, null, null, algorithm);
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
