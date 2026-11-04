package io.github.temporalrift.workbench.search.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignFailure;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.wave.Game;
import io.github.temporalrift.workbench.shared.execution.RunPhase;

class CampaignProgressTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void aCampaignWaitsInTheQueueWhileTheFidelityIsNotEstablishedAndStartsWhenItIs() {
        var harness = new Harness(3600);
        harness.play.fidelity(false);
        var id = harness.create(4);

        assertThat(harness.progress.advance()).isTrue();
        assertThat(harness.progress.advance()).isFalse();

        var waiting = harness.campaign(id);
        assertThat(waiting.state()).isEqualTo(CampaignState.QUEUED);
        assertThat(waiting.failure().code()).isEqualTo(CampaignFailure.FIDELITY_NOT_ESTABLISHED);

        harness.play.fidelity(true);
        assertThat(harness.progress.advance()).isTrue();

        var started = harness.campaign(id);
        assertThat(started.state()).isEqualTo(CampaignState.RUNNING);
        assertThat(started.failure()).isNull();
        assertThat(started.startedAt()).isEqualTo(START);
    }

    @Test
    void aSearchRunsToItsShortListAndEveryGameIsRecordedOnce() {
        var harness = new Harness(3600);
        var id = harness.create(4);

        harness.drive(id);

        var campaign = harness.campaign(id);
        assertThat(campaign.state()).isEqualTo(CampaignState.SEARCH_COMPLETED);
        assertThat(campaign.shortList()).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        var log = harness.campaigns.log(id, 1000, 0).items();
        assertThat(log).extracting(LogEntry::sequence).isEqualTo(sequence(log.size()));
        assertThat(log)
                .filteredOn(entry -> entry.decision() == LogEntry.Decision.GENERATED)
                .hasSizeGreaterThanOrEqualTo(8);
        assertThat(log).extracting(LogEntry::decision).contains(LogEntry.Decision.SHORT_LISTED);
        assertThat(log)
                .filteredOn(entry -> entry.reason() == LogEntry.Reason.GATE_FAILS)
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.evidence().lineIds()).isNotEmpty());
        var games = harness.campaigns.games(id);
        assertThat(games.stream().map(game -> game.candidateId() + "/" + game.seed()))
                .doesNotHaveDuplicates();
        var finalSeeds = new HashSet<>(games.stream().map(Game::seed).toList());
        assertThat(finalSeeds).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void theLogIsTheSameWhateverTheParallelismAndWhetherOrNotTheCampaignWasPausedInRoundOne() {
        var straight = new Harness(3600);
        var straightId = straight.create(1);
        straight.drive(straightId);

        var interrupted = new Harness(3600);
        var interruptedId = interrupted.create(4);
        interrupted.driveUntil(() -> interrupted.campaign(interruptedId).round() == 1
                && !interrupted.campaigns.waves(interruptedId).isEmpty()
                && interrupted.campaigns.waves(interruptedId).stream().anyMatch(wave -> wave.round() == 1));
        interrupted.pause.handle(interruptedId);
        var inPlay = interrupted.campaigns.waves(interruptedId).stream()
                .filter(wave -> interrupted.play.phaseOf(wave.runId()) == RunPhase.ACTIVE)
                .toList();
        assertThat(inPlay).isNotEmpty();
        inPlay.forEach(
                wave -> assertThat(interrupted.play.concurrencyOf(wave.runId())).isZero());
        interrupted.clock.advance(Duration.ofMinutes(30));
        interrupted.resume.handle(interruptedId);
        interrupted.parallelism.handle(interruptedId, 2);
        interrupted.drive(interruptedId);

        assertThat(interrupted.campaign(interruptedId).state()).isEqualTo(CampaignState.SEARCH_COMPLETED);
        assertThat(interrupted.campaigns.canonicalLog(interruptedId))
                .isNotEmpty()
                .isEqualTo(straight.campaigns.canonicalLog(straightId));
        assertThat(interrupted.campaign(interruptedId).shortList())
                .isEqualTo(straight.campaign(straightId).shortList());
    }

    @Test
    void aRestartedCampaignEndsWithTheLogOfAnUninterruptedOneAndNoGameCountedTwice() {
        var uninterrupted = new Harness(3600);
        var uninterruptedId = uninterrupted.create(4);
        uninterrupted.drive(uninterruptedId);

        var restarted = new Harness(3600);
        var restartedId = restarted.create(4);
        restarted.driveUntil(() -> restarted.campaign(restartedId).round() == 2);
        restarted.play.restart();
        restarted.restartProcess();
        restarted.drive(restartedId);

        assertThat(restarted.campaign(restartedId).state()).isEqualTo(CampaignState.SEARCH_COMPLETED);
        assertThat(restarted.campaigns.canonicalLog(restartedId))
                .isEqualTo(uninterrupted.campaigns.canonicalLog(uninterruptedId));
        assertThat(restarted.campaigns.games(restartedId).stream().map(game -> game.candidateId() + "/" + game.seed()))
                .doesNotHaveDuplicates()
                .hasSize(uninterrupted.campaigns.games(uninterruptedId).size());
    }

    @Test
    void onlyTheTimeSpentRunningCountsAgainstTheBudget() {
        var harness = new Harness(3600);
        var id = harness.create(4);
        harness.progress.advance();
        harness.progress.advance();

        harness.clock.advance(Duration.ofSeconds(100));
        harness.progress.advance();
        harness.pause.handle(id);
        harness.clock.advance(Duration.ofSeconds(1000));
        harness.resume.handle(id);
        harness.clock.advance(Duration.ofSeconds(50));
        harness.pause.handle(id);

        assertThat(harness.campaign(id).spentSeconds()).isEqualTo(150);
    }

    @Test
    void timeTheProcessWasDownIsNotCharged() {
        var harness = new Harness(3600);
        var id = harness.create(4);
        harness.progress.advance();
        harness.progress.advance();
        harness.clock.advance(Duration.ofSeconds(40));
        harness.progress.advance();

        harness.clock.advance(Duration.ofHours(3));
        harness.restartProcess();
        harness.clock.advance(Duration.ofSeconds(10));
        harness.progress.advance();

        assertThat(harness.campaign(id).spentSeconds()).isEqualTo(50);
    }

    @Test
    void theBudgetEndsTheSearchWhileARoundIsInPlay() {
        var harness = new Harness(120);
        var id = harness.create(4);
        harness.progress.advance();
        harness.progress.advance();
        harness.progress.advance();
        harness.clock.advance(Duration.ofSeconds(300));

        harness.progress.advance();

        var campaign = harness.campaign(id);
        assertThat(campaign.state()).isEqualTo(CampaignState.SEARCH_COMPLETED);
        var log = harness.campaigns.log(id, 1000, 0).items();
        assertThat(log)
                .filteredOn(entry -> entry.decision() == LogEntry.Decision.DISCARDED)
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.reason()).isEqualTo(LogEntry.Reason.BUDGET_EXHAUSTED));
        harness.campaigns
                .waves(id)
                .forEach(wave -> assertThat(harness.play.phaseOf(wave.runId())).isEqualTo(RunPhase.CANCELLED));
    }

    @Test
    void aWaveThatFailsFailsTheCampaignAndStopsItsRuns() {
        var harness = new Harness(3600);
        var id = harness.create(4);
        harness.progress.advance();
        harness.progress.advance();
        harness.progress.advance();
        var wave = harness.campaigns.waves(id).getFirst();
        harness.play.fail(wave.runId(), "lane exploded");

        harness.progress.advance();

        var campaign = harness.campaign(id);
        assertThat(campaign.state()).isEqualTo(CampaignState.FAILED);
        assertThat(campaign.failure().code()).isEqualTo(CampaignProgress.RUN_FAILED);
        assertThat(campaign.failure().message()).contains("lane exploded");
    }

    @Test
    void cancellingStopsEveryRunAndKeepsTheLog() {
        var harness = new Harness(3600);
        var id = harness.create(4);
        harness.progress.advance();
        harness.progress.advance();
        harness.progress.advance();

        harness.cancel.handle(id);

        assertThat(harness.campaign(id).state()).isEqualTo(CampaignState.CANCELLED);
        harness.campaigns
                .waves(id)
                .forEach(wave -> assertThat(harness.play.phaseOf(wave.runId())).isEqualTo(RunPhase.CANCELLED));
        assertThat(harness.campaigns.log(id, 1, 0).total()).isPositive();
    }

    @Test
    void aPauseLandingBetweenReadingTheCampaignAndRaisingTheRunsLeavesTheRunsHeld() {
        var harness = new Harness(3600);
        var id = harness.create(4);
        harness.progress.advance();
        harness.progress.advance();
        harness.progress.advance();
        harness.play.beforeNextRaise(() -> harness.pause.handle(id));

        harness.progress.advance();

        assertThat(harness.campaign(id).state()).isEqualTo(CampaignState.PAUSED);
        var waves = harness.campaigns.waves(id);
        assertThat(waves).isNotEmpty();
        waves.forEach(
                wave -> assertThat(harness.play.concurrencyOf(wave.runId())).isZero());
    }

    @Test
    void aCampaignWhoseBudgetRunsOutBetweenRoundsEndsWithTheShortListOfTheLastDecidedRound() {
        var harness = new Harness(3600);
        var id = harness.create(4);
        harness.driveUntil(() -> harness.campaign(id).round() == 1
                && harness.campaigns.candidates(id).stream().noneMatch(candidate -> candidate.round() == 1));
        harness.clock.advance(Duration.ofHours(2));

        harness.progress.advance();

        var campaign = harness.campaign(id);
        assertThat(campaign.state()).isEqualTo(CampaignState.SEARCH_COMPLETED);
        assertThat(campaign.shortList()).isNotEmpty();
    }

    @Test
    void aWaveNeverHoldsMoreCasesThanTheLimitSoSeedsAreSplitAsWellAsCandidates() {
        var harness = new Harness(3600, 15);
        var id = harness.create(4);

        harness.drive(id);

        assertThat(harness.campaign(id).state()).isEqualTo(CampaignState.SEARCH_COMPLETED);
        assertThat(harness.play.frozen())
                .isNotEmpty()
                .allSatisfy(experiment -> assertThat(experiment.candidates().size()
                                * experiment.seeds().size())
                        .isEqualTo(1));
        assertThat(harness.campaigns.games(id).stream().map(game -> game.candidateId() + "/" + game.seed()))
                .doesNotHaveDuplicates();
    }

    @Test
    void aCampaignThatCannotBeSteppedDoesNotStopTheCampaignsBehindIt() {
        var harness = new Harness(3600);
        harness.create(4, SimulatedPlay.POISONED);
        var healthy = harness.create(4);

        for (var step = 0; step < 6; step++) {
            harness.progress.advance();
        }

        assertThat(harness.campaigns.candidates(healthy)).isNotEmpty();
    }

    private static List<Integer> sequence(int size) {
        return java.util.stream.IntStream.rangeClosed(1, size).boxed().toList();
    }

    /** A campaign service over in-memory storage and simulated play, with a clock the test moves. */
    private static final class Harness {

        final InMemoryCampaigns campaigns = new InMemoryCampaigns();
        final SimulatedPlay play = new SimulatedPlay();
        final TestClock clock = new TestClock(START);
        final int budgetSeconds;
        final int maxCasesPerWave;
        CampaignProgress progress;
        final PauseCampaignCommandHandler pause;
        final ResumeCampaignCommandHandler resume;
        final CancelCampaignCommandHandler cancel;
        final SetCampaignParallelismCommandHandler parallelism;

        Harness(int budgetSeconds) {
            this(budgetSeconds, 1_000);
        }

        Harness(int budgetSeconds, int maxCasesPerWave) {
            this.budgetSeconds = budgetSeconds;
            this.maxCasesPerWave = maxCasesPerWave;
            this.progress = newProgress();
            this.pause = new PauseCampaignCommandHandler(campaigns, play, clock);
            this.resume = new ResumeCampaignCommandHandler(campaigns, play, clock);
            this.cancel = new CancelCampaignCommandHandler(campaigns, play, clock);
            this.parallelism = new SetCampaignParallelismCommandHandler(campaigns, play, clock);
        }

        private CampaignProgress newProgress() {
            return new CampaignProgress(
                    campaigns, play, play, play, play, new CampaignSettings(maxCasesPerWave), clock);
        }

        /** A new process over the same storage: nothing is remembered but what was persisted. */
        void restartProcess() {
            progress = newProgress();
            progress.recover();
        }

        UUID create(int parallelism) {
            return create(parallelism, "search");
        }

        UUID create(int parallelism, String name) {
            var mapper = JsonMapper.builder().build();
            var baseline = mapper.createObjectNode();
            baseline.putObject("rulesArtifact").put("digest", "d".repeat(64));
            var definition = new CampaignDefinition(
                    name,
                    baseline,
                    1,
                    1,
                    mapper.createArrayNode().add(mapper.createObjectNode().put("id", "random")),
                    List.of(3),
                    budgetSeconds,
                    "7",
                    "9",
                    new SearchAlgorithm(8, 2, 2, 2));
            var campaign = Campaign.queued(UUID.randomUUID(), definition, parallelism, START);
            campaigns.create(UUID.randomUUID(), "h".repeat(64), campaign, START);
            return campaign.campaignId();
        }

        Campaign campaign(UUID id) {
            return campaigns.find(id).orElseThrow().campaign();
        }

        void drive(UUID id) {
            driveUntil(
                    () -> campaign(id).state().isTerminal() || campaign(id).state() == CampaignState.SEARCH_COMPLETED);
        }

        void driveUntil(java.util.function.BooleanSupplier done) {
            for (var step = 0; step < 2_000 && !done.getAsBoolean(); step++) {
                var moved = progress.advance();
                if (done.getAsBoolean()) {
                    return;
                }
                var played = play.playAllowed();
                if (!moved && !played) {
                    return;
                }
            }
        }
    }

    private static final class TestClock extends Clock {

        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
