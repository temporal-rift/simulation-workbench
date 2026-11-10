package io.github.temporalrift.workbench.search.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.campaign.SimulatorNotAvailableException;

/** What a campaign command does with its idempotency key, whichever command it is. */
class CampaignCommandKeyTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID KEY = new UUID(9, 9);

    private final InMemoryCampaigns campaigns = new InMemoryCampaigns();
    private final SimulatedPlay play = new SimulatedPlay();
    private final PauseCampaignCommandHandler pause = new PauseCampaignCommandHandler(campaigns, play, CLOCK);
    private final ResumeCampaignCommandHandler resume = new ResumeCampaignCommandHandler(campaigns, play, CLOCK);
    private final CancelCampaignCommandHandler cancel = new CancelCampaignCommandHandler(campaigns, play, CLOCK);
    private final SetCampaignParallelismCommandHandler parallelism =
            new SetCampaignParallelismCommandHandler(campaigns, play, CLOCK);
    private final StartConfirmationCommandHandler confirm =
            new StartConfirmationCommandHandler(campaigns, play, play, play, CLOCK);

    @Test
    void aRepeatedKeyDoesNotApplyTheCommandAgainAndReturnsTheCampaignAsItStands() {
        var id = running();
        pause.handle(id, KEY);
        resume.handle(id, UUID.randomUUID());

        var repeated = pause.handle(id, KEY);

        assertThat(repeated.state()).isEqualTo(CampaignState.RUNNING);
        assertThat(state(id)).isEqualTo(CampaignState.RUNNING);
    }

    @Test
    void aPauseRepeatedAfterAResumeDoesNotHoldTheRunsAgain() {
        var id = running();
        var runId = play.start(UUID.randomUUID(), UUID.randomUUID());
        campaigns.insertWave(id, new io.github.temporalrift.workbench.search.domain.wave.Wave(0, 0, runId, runId));
        pause.handle(id, KEY);
        resume.handle(id, UUID.randomUUID());
        assertThat(play.concurrencyOf(runId)).isEqualTo(4);

        pause.handle(id, KEY);

        assertThat(play.concurrencyOf(runId)).isEqualTo(4);
    }

    @Test
    void aPauseRepeatedWhileStillPausedHoldsTheRunsAgainInCaseTheFirstAttemptStoppedBeforeHoldingThem() {
        var id = running();
        var runId = play.start(UUID.randomUUID(), UUID.randomUUID());
        campaigns.insertWave(id, new io.github.temporalrift.workbench.search.domain.wave.Wave(0, 0, runId, runId));
        pause.handle(id, KEY);
        play.setConcurrency(runId, 4);

        pause.handle(id, KEY);

        assertThat(play.concurrencyOf(runId)).isZero();
    }

    @Test
    void aConfirmationRepeatedAfterItsResponseWasLostReturnsTheConfirmingCampaign() {
        var id = searched();
        var first = confirm.handle(id, KEY);

        var repeated = confirm.handle(id, KEY);

        assertThat(repeated.state()).isEqualTo(CampaignState.CONFIRMING);
        assertThat(repeated.confirmation()).isEqualTo(first.confirmation());
    }

    @Test
    void aConfirmationWhoseWriteWasNeverStoredClaimsNothingAndARetryApplies() {
        var id = searched();
        // the state a crash leaves behind: the effect and the claim are one write, so neither exists
        assertThat(campaigns.claimedRequestHash(KEY)).isEmpty();
        assertThat(state(id)).isEqualTo(CampaignState.SEARCH_COMPLETED);

        assertThat(confirm.handle(id, KEY).state()).isEqualTo(CampaignState.CONFIRMING);
        assertThat(campaigns.claimedRequestHash(KEY)).isPresent();
    }

    @Test
    void aRequestThatLosesARaceToItsOwnKeyAnswersWithTheWinnersResultAndAppliesNothing() {
        var id = running();
        var other = new SetCampaignParallelismCommandHandler(campaigns, play, CLOCK);
        campaigns.beforeNextApply(() -> other.handle(id, 8, KEY));

        var loser = parallelism.handle(id, 8, KEY);

        assertThat(loser.parallelism()).isEqualTo(8);
        assertThat(campaigns.find(id).orElseThrow().version())
                .as("the campaign was written once, by the winner")
                .isEqualTo(2);
    }

    @Test
    void aRequestRacedByAnotherRequestWithItsKeyConflictsAndAppliesNothing() {
        var id = running();
        var other = new SetCampaignParallelismCommandHandler(campaigns, play, CLOCK);
        campaigns.beforeNextApply(() -> other.handle(id, 8, KEY));

        assertThatThrownBy(() -> parallelism.handle(id, 2, KEY))
                .isInstanceOf(CampaignIdempotencyConflictException.class);

        assertThat(campaigns.find(id).orElseThrow().campaign().parallelism()).isEqualTo(8);
    }

    @Test
    void aConfirmationThatLosesARaceToItsOwnKeyAnswersWithTheWinnersConfirmation() {
        var id = searched();
        campaigns.beforeNextApply(() -> confirm.handle(id, KEY));

        var loser = confirm.handle(id, KEY);

        assertThat(loser.state()).isEqualTo(CampaignState.CONFIRMING);
    }

    @Test
    void aConfirmationWhoseKeyIsClaimedBetweenTheKeyCheckAndTheStateCheckAnswersWithTheCampaign() {
        var id = searched();
        campaigns.beforeNextFind(() -> confirm.handle(id, KEY));

        var duplicate = confirm.handle(id, KEY);

        assertThat(duplicate.state()).isEqualTo(CampaignState.CONFIRMING);
    }

    @Test
    void aCommandWhoseKeyIsClaimedBetweenTheKeyCheckAndTheStateCheckAnswersWithTheCampaign() {
        var id = running();
        campaigns.beforeNextFind(() -> {
            pause.handle(id, KEY);
            cancel.handle(id, UUID.randomUUID());
        });

        var duplicate = pause.handle(id, KEY);

        assertThat(duplicate.state()).isEqualTo(CampaignState.CANCELLED);
    }

    @Test
    void aRepeatedConfirmationAnswersWithTheCampaignAsItStandsAfterItWasCancelled() {
        var id = searched();
        confirm.handle(id, KEY);
        cancel.handle(id, UUID.randomUUID());

        assertThat(confirm.handle(id, KEY).state()).isEqualTo(CampaignState.CANCELLED);
    }

    @Test
    void aKeyReusedWithAnotherValueConflictsAndChangesNothing() {
        var id = running();
        parallelism.handle(id, 8, KEY);

        assertThatThrownBy(() -> parallelism.handle(id, 2, KEY))
                .isInstanceOf(CampaignIdempotencyConflictException.class);

        assertThat(campaigns.find(id).orElseThrow().campaign().parallelism()).isEqualTo(8);
    }

    @Test
    void aKeyReusedOnAnotherOperationOrCampaignConflicts() {
        var id = running();
        var otherCampaign = running();
        pause.handle(id, KEY);

        assertThatThrownBy(() -> resume.handle(id, KEY)).isInstanceOf(CampaignIdempotencyConflictException.class);
        assertThatThrownBy(() -> pause.handle(otherCampaign, KEY))
                .isInstanceOf(CampaignIdempotencyConflictException.class);
        assertThatThrownBy(() -> cancel.handle(id, KEY)).isInstanceOf(CampaignIdempotencyConflictException.class);
    }

    @Test
    void aKeyThatCreatedACampaignConflictsWithACommand() {
        var id = running();
        var created = UUID.randomUUID();
        campaigns.create(created, "h".repeat(64), Campaign.queued(UUID.randomUUID(), definition(), 4, NOW), NOW);

        assertThatThrownBy(() -> pause.handle(id, created)).isInstanceOf(CampaignIdempotencyConflictException.class);
    }

    @Test
    void anUnknownCampaignIsNotFoundAndClaimsNothing() {
        var unknown = UUID.randomUUID();

        assertThatThrownBy(() -> pause.handle(unknown, KEY)).isInstanceOf(CampaignNotFoundException.class);

        assertThat(campaigns.claimedRequestHash(KEY)).isEmpty();
    }

    @Test
    void aRefusedCommandClaimsNothingSoItCanBeAskedAgainWithTheSameKey() {
        var id = searched();
        play.simulator(false);

        assertThatThrownBy(() -> confirm.handle(id, KEY)).isInstanceOf(SimulatorNotAvailableException.class);
        assertThat(campaigns.claimedRequestHash(KEY)).isEmpty();

        play.simulator(true);

        assertThat(confirm.handle(id, KEY).state()).isEqualTo(CampaignState.CONFIRMING);
    }

    private CampaignState state(UUID id) {
        return campaigns.find(id).orElseThrow().campaign().state();
    }

    private UUID running() {
        var queued = Campaign.queued(UUID.randomUUID(), definition(), 4, NOW);
        campaigns.create(UUID.randomUUID(), "h".repeat(64), queued, NOW);
        var stored = campaigns.find(queued.campaignId()).orElseThrow();
        campaigns.apply(CampaignChange.of(stored, queued.start(NOW), NOW));
        return queued.campaignId();
    }

    /** A campaign whose search ended with nothing short-listed, which is all a repeated key needs. */
    private UUID searched() {
        var id = running();
        var stored = campaigns.find(id).orElseThrow();
        campaigns.apply(CampaignChange.of(stored, stored.campaign().completeSearch(List.of()), NOW));
        return id;
    }

    private static CampaignDefinition definition() {
        var mapper = JsonMapper.builder().build();
        var baseline = mapper.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", "d".repeat(64));
        return new CampaignDefinition(
                "search",
                baseline,
                1,
                1,
                mapper.createArrayNode().add(mapper.createObjectNode().put("id", "random")),
                List.of(3),
                3600,
                "7",
                "9",
                new SearchAlgorithm(SearchAlgorithm.Version.SUCCESSIVE_HALVING_V1, 8, 2, 2, 2));
    }
}
