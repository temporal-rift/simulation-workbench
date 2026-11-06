package io.github.temporalrift.workbench.search.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignFailure;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.Confirmation;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException;
import io.github.temporalrift.workbench.search.domain.campaign.NoRealLaneException;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.campaign.SeedOverlapException;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.ConfirmationSeeds;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.shared.execution.RunPhase;

class ConfirmationTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryCampaigns campaigns = new InMemoryCampaigns();
    private final SimulatedPlay play = new SimulatedPlay();
    private final StartConfirmationCommandHandler start =
            new StartConfirmationCommandHandler(campaigns, play, play, play);
    private final CampaignProgress progress =
            new CampaignProgress(campaigns, play, play, play, play, new CampaignSettings(1_000), CLOCK);
    private final CancelCampaignCommandHandler cancel = new CancelCampaignCommandHandler(campaigns, play, CLOCK);

    @Test
    void aShortListOfTwoFreezesThreeExperimentsOfHundredFreshSeedsEach() {
        var id = searched("c-0000", "c-0001");

        var confirming = start.handle(id);

        assertThat(confirming.state()).isEqualTo(CampaignState.CONFIRMING);
        var confirmation = confirming.confirmation();
        assertThat(confirmation.seedCount()).isEqualTo(100);
        assertThat(confirmation.runs())
                .extracting(Confirmation.Run::subject)
                .containsExactly("baseline", "c-0000", "c-0001");
        var expected = new ConfirmationSeeds("9").seeds(100);
        assertThat(play.frozen())
                .hasSize(3)
                .allSatisfy(experiment -> assertThat(experiment.seeds()).isEqualTo(expected));
        assertThat(campaigns.find(id).orElseThrow().campaign().confirmation()).isEqualTo(confirmation);
    }

    @Test
    void aConfirmationSeedAlreadyInTheLogIsRefusedAndNothingIsFrozen() {
        var id = searched("c-0000");
        var shared = new ConfirmationSeeds("9").seeds(100).get(37);
        campaigns.appendLog(id, List.of(logged(1, List.of("1", shared))));

        assertThatThrownBy(() -> start.handle(id))
                .isInstanceOfSatisfying(
                        SeedOverlapException.class, e -> assertThat(e.seeds()).containsExactly(shared));

        assertThat(play.frozen()).isEmpty();
        assertThat(play.runIds()).isEmpty();
        assertThat(state(id)).isEqualTo(CampaignState.SEARCH_COMPLETED);
    }

    @Test
    void confirmationIsRefusedFromAnyStateButSearchCompleted() {
        var id = searched("c-0000");
        start.handle(id);

        assertThatThrownBy(() -> start.handle(id)).isInstanceOf(InvalidCampaignStateException.class);
    }

    @Test
    void withoutAnEquivalentRealLaneNothingIsFrozenOrStartedAndTheCampaignStaysPut() {
        var id = searched("c-0000");
        play.realLane(false);

        assertThatThrownBy(() -> start.handle(id)).isInstanceOf(NoRealLaneException.class);

        assertThat(play.frozen()).isEmpty();
        assertThat(play.runIds()).isEmpty();
        assertThat(state(id)).isEqualTo(CampaignState.SEARCH_COMPLETED);
    }

    @Test
    void aConfirmationRefusedForWantOfALaneCanBeStartedOnceOneExists() {
        var id = searched("c-0000");
        play.realLane(false);
        assertThatThrownBy(() -> start.handle(id)).isInstanceOf(NoRealLaneException.class);
        play.realLane(true);

        var confirming = start.handle(id);

        assertThat(confirming.state()).isEqualTo(CampaignState.CONFIRMING);
        assertThat(concurrencies(confirming.confirmation().runs())).containsExactly(1, 0);
    }

    @Test
    void aSetupThatFailsAfterStartingARunLeavesItHeldAndARetryReusesIt() {
        var id = searched("c-0000", "c-0001");
        play.refuseConfirmationFreezeNumber(2);

        assertThatThrownBy(() -> start.handle(id)).isInstanceOf(CampaignPlayRefusedException.class);

        assertThat(state(id)).isEqualTo(CampaignState.SEARCH_COMPLETED);
        var orphan = play.runIds().getFirst();
        assertThat(play.concurrencyOf(orphan))
                .as("nothing plays before the campaign records the runs")
                .isZero();
        assertThat(play.playAllowed()).isFalse();

        var confirming = start.handle(id);

        assertThat(confirming.confirmation().runs().getFirst().runId()).isEqualTo(orphan);
        assertThat(play.runIds()).hasSize(3);
        assertThat(concurrencies(confirming.confirmation().runs())).containsExactly(1, 0, 0);
    }

    @Test
    void whenNoLaneFitsTheRunsTheConfirmationIsRefusedAsHavingNoRealLaneAndLeavesNothingPlaying() {
        var id = searched("c-0000");
        play.runsFit(false);

        assertThatThrownBy(() -> start.handle(id)).isInstanceOf(NoRealLaneException.class);

        assertThat(play.runIds()).isEmpty();
        assertThat(state(id)).isEqualTo(CampaignState.SEARCH_COMPLETED);
    }

    @Test
    void onlyTheFirstRunMayPlayAndEachNextOneIsReleasedWhenTheOneBeforeIsDone() {
        var id = searched("c-0000", "c-0001");
        var runs = start.handle(id).confirmation().runs();

        assertThat(concurrencies(runs)).containsExactly(1, 0, 0);

        progress.advance();
        assertThat(concurrencies(runs)).containsExactly(1, 0, 0);

        complete(runs.get(0));
        progress.advance();
        assertThat(concurrencies(runs)).containsExactly(1, 1, 0);

        complete(runs.get(1));
        progress.advance();
        assertThat(concurrencies(runs).get(2)).isEqualTo(1);
    }

    @Test
    void whenEveryRunIsCompleteTheEvaluationsAreRecordedAndTheCampaignIsCompleted() {
        var id = searched("c-0000", "c-0001");
        play.confirmationOf("baseline", "UNCONFIRMED", 4.5);
        play.confirmationOf("c-0001", "REJECTED", 7.0);
        var runs = start.handle(id).confirmation().runs();
        runs.forEach(this::complete);

        assertThat(progress.advance()).isTrue();

        var completed = campaigns.find(id).orElseThrow().campaign();
        assertThat(completed.state()).isEqualTo(CampaignState.COMPLETED);
        assertThat(completed.finishedAt()).isEqualTo(NOW);
        assertThat(completed.confirmation().runs())
                .extracting(run -> run.subject() + ":" + run.evaluation().verdict() + ":"
                        + run.evaluation().score())
                .containsExactly("baseline:UNCONFIRMED:4.5", "c-0000:CONFIRMED:1.0", "c-0001:REJECTED:7.0");
    }

    @Test
    void aFailedConfirmationRunFailsTheCampaignAndStopsTheOthers() {
        var id = searched("c-0000");
        var runs = start.handle(id).confirmation().runs();
        play.fail(runs.get(0).runId(), "lane lost");

        progress.advance();

        var failed = campaigns.find(id).orElseThrow().campaign();
        assertThat(failed.state()).isEqualTo(CampaignState.FAILED);
        assertThat(failed.failure().code()).isEqualTo(CampaignFailure.CONFIRMATION_RUN_FAILED);
        assertThat(failed.failure().message()).contains("baseline").contains("lane lost");
        assertThat(play.phaseOf(runs.get(1).runId())).isEqualTo(RunPhase.CANCELLED);
    }

    @Test
    void anInterruptedRunIsResumedAfterARestart() {
        var id = searched("c-0000");
        var runs = start.handle(id).confirmation().runs();
        play.restart();

        progress.advance();

        assertThat(play.phaseOf(runs.get(0).runId())).isEqualTo(RunPhase.ACTIVE);
        assertThat(play.concurrencyOf(runs.get(0).runId())).isEqualTo(1);
    }

    @Test
    void cancellingAConfirmingCampaignCancelsItsConfirmationRuns() {
        var id = searched("c-0000");
        var runs = start.handle(id).confirmation().runs();

        var cancelled = cancel.handle(id);

        assertThat(cancelled.state()).isEqualTo(CampaignState.CANCELLED);
        runs.forEach(run -> assertThat(play.phaseOf(run.runId())).isEqualTo(RunPhase.CANCELLED));
    }

    @Test
    void askingAgainAfterTheRunsWereStartedConvergesOnTheSameExperimentsAndRuns() {
        var id = searched("c-0000");
        var first = start.handle(id).confirmation();
        // a request that crashed after freezing: the campaign is still searched, the experiments and runs exist
        var stored = campaigns.find(id).orElseThrow();
        var reverted = new Campaign(
                id,
                stored.campaign().definition(),
                stored.campaign().parallelism(),
                CampaignState.SEARCH_COMPLETED,
                0,
                0,
                stored.campaign().shortList(),
                null,
                null,
                NOW,
                NOW,
                null);
        campaigns.save(reverted, NOW, stored.version());

        var second = start.handle(id).confirmation();

        assertThat(second.runs()).isEqualTo(first.runs());
        assertThat(play.frozen()).hasSize(2);
    }

    private List<Integer> concurrencies(List<Confirmation.Run> runs) {
        return runs.stream().map(run -> play.concurrencyOf(run.runId())).toList();
    }

    private void complete(Confirmation.Run run) {
        play.finish(run.runId());
    }

    private CampaignState state(UUID id) {
        return campaigns.find(id).orElseThrow().campaign().state();
    }

    private static LogEntry logged(int sequence, List<String> seeds) {
        return new LogEntry(
                sequence,
                0,
                "c-0000",
                new TreeMap<>(),
                "d".repeat(64),
                seeds,
                seeds.size(),
                0,
                null,
                LogEntry.Decision.KEPT,
                null,
                null);
    }

    /** A campaign whose search is complete with the given short list. */
    private UUID searched(String... shortList) {
        var mapper = JsonMapper.builder().build();
        var baseline = mapper.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", "d".repeat(64));
        var definition = new CampaignDefinition(
                "search",
                baseline,
                1,
                1,
                mapper.createArrayNode().add(mapper.createObjectNode().put("id", "random")),
                List.of(3),
                3600,
                "7",
                "9",
                new SearchAlgorithm(8, 2, 2, 2));
        var queued = Campaign.queued(UUID.randomUUID(), definition, 4, NOW);
        campaigns.create(UUID.randomUUID(), "h".repeat(64), queued, NOW);
        var candidates = new java.util.ArrayList<Candidate>();
        for (var index = 0; index < shortList.length; index++) {
            var overrides = new TreeMap<String, BigDecimal>();
            overrides.put("game.rules.a", BigDecimal.valueOf(index + 1));
            candidates.add(new Candidate(shortList[index], 0, List.of(index), overrides, "e".repeat(64), 0));
        }
        campaigns.insertCandidates(queued.campaignId(), candidates);
        var stored = campaigns.find(queued.campaignId()).orElseThrow();
        var searchDone = queued.start(NOW).completeSearch(List.of(shortList));
        campaigns.apply(CampaignChange.of(stored, searchDone, NOW));
        return queued.campaignId();
    }
}
