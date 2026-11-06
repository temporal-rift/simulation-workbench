package io.github.temporalrift.workbench.search.domain.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class CampaignTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Campaign queued = Campaign.queued(
            new UUID(1, 1),
            new CampaignDefinition(
                    "search",
                    JSON.createObjectNode(),
                    1,
                    1,
                    JSON.createArrayNode(),
                    List.of(3, 4),
                    CampaignDefinition.DEFAULT_BUDGET_SECONDS,
                    "1",
                    "2",
                    new SearchAlgorithm(32, 2, 2, 1)),
            CampaignDefinition.DEFAULT_PARALLELISM,
            NOW);

    @Test
    void aCampaignIsAcceptedQueuedWithNothingSpent() {
        assertThat(queued.state()).isEqualTo(CampaignState.QUEUED);
        assertThat(queued.spentSeconds()).isZero();
        assertThat(queued.round()).isZero();
        assertThat(queued.shortList()).isEmpty();
        assertThat(queued.failure()).isNull();
        assertThat(queued.startedAt()).isNull();
        assertThat(queued.finishedAt()).isNull();
    }

    @Test
    void pausingAndResumingRoundTripAndRepeatsChangeNothing() {
        var running = queued.start(NOW);

        var paused = running.pause();
        assertThat(paused.state()).isEqualTo(CampaignState.PAUSED);
        assertThat(paused.pause()).isSameAs(paused);

        var resumed = paused.resume();
        assertThat(resumed.state()).isEqualTo(CampaignState.RUNNING);
        assertThat(resumed.resume()).isSameAs(resumed);
    }

    @Test
    void aQueuedCampaignPausesBeforeSpendingAnyBudget() {
        var paused = queued.pause();

        assertThat(paused.state()).isEqualTo(CampaignState.PAUSED);
        assertThat(paused.spentSeconds()).isZero();
        assertThat(paused.startedAt()).isNull();
    }

    @Test
    void aCampaignResumedBeforeItStartedRecordsItsStartWhenItDoes() {
        var resumed = queued.pause().resume();

        assertThat(resumed.state()).isEqualTo(CampaignState.RUNNING);
        assertThat(resumed.startedAt()).isNull();
        assertThat(resumed.start(NOW).startedAt()).isEqualTo(NOW);
    }

    @Test
    void aCampaignThatCannotStartYetWaitsWithItsReasonAndStartsLater() {
        var reason = new CampaignFailure(CampaignFailure.FIDELITY_NOT_ESTABLISHED, "no passing fidelity record");

        var waiting = queued.waiting(reason);
        assertThat(waiting.state()).isEqualTo(CampaignState.QUEUED);
        assertThat(waiting.failure()).isEqualTo(reason);

        var started = waiting.start(NOW);
        assertThat(started.state()).isEqualTo(CampaignState.RUNNING);
        assertThat(started.failure()).isNull();
        assertThat(started.startedAt()).isEqualTo(NOW);
    }

    @Test
    void aResumedCampaignThatNeverStartedGoesBackToTheQueueWhenItCannotStart() {
        var reason = new CampaignFailure(CampaignFailure.FIDELITY_NOT_ESTABLISHED, "no passing fidelity record");

        var requeued = queued.pause().resume().requeue(reason);

        assertThat(requeued.state()).isEqualTo(CampaignState.QUEUED);
        assertThat(requeued.failure()).isEqualTo(reason);
        var started = queued.start(NOW);
        assertThatThrownBy(() -> started.requeue(reason)).isInstanceOf(InvalidCampaignStateException.class);
    }

    @Test
    void cancelIsLegalBeforeATerminalStateAndRepeatsChangeNothing() {
        var cancelled = queued.start(NOW).cancel(NOW.plusSeconds(5));

        assertThat(cancelled.state()).isEqualTo(CampaignState.CANCELLED);
        assertThat(cancelled.finishedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(cancelled.cancel(NOW.plusSeconds(9))).isSameAs(cancelled);
    }

    @ParameterizedTest
    @EnumSource(
            value = CampaignState.class,
            names = {"COMPLETED", "FAILED"})
    void aFinishedCampaignRefusesEveryWrite(CampaignState terminal) {
        var finished = in(terminal);

        assertThatThrownBy(finished::pause).isInstanceOf(InvalidCampaignStateException.class);
        assertThatThrownBy(finished::resume).isInstanceOf(InvalidCampaignStateException.class);
        assertThatThrownBy(() -> finished.cancel(NOW)).isInstanceOf(InvalidCampaignStateException.class);
        assertThatThrownBy(() -> finished.withParallelism(2)).isInstanceOf(InvalidCampaignStateException.class);
    }

    @Test
    void aCancelledCampaignRefusesEveryWriteButCancel() {
        var cancelled = queued.cancel(NOW);

        assertThatThrownBy(cancelled::pause).isInstanceOf(InvalidCampaignStateException.class);
        assertThatThrownBy(cancelled::resume).isInstanceOf(InvalidCampaignStateException.class);
        assertThatThrownBy(() -> cancelled.withParallelism(2)).isInstanceOf(InvalidCampaignStateException.class);
    }

    @Test
    void aCompletedSearchRefusesPauseAndResumeButStillTakesAParallelismChange() {
        var searched = queued.start(NOW).completeSearch(List.of("c-007"));

        assertThat(searched.state()).isEqualTo(CampaignState.SEARCH_COMPLETED);
        assertThat(searched.shortList()).containsExactly("c-007");
        assertThatThrownBy(searched::pause).isInstanceOf(InvalidCampaignStateException.class);
        assertThatThrownBy(searched::resume).isInstanceOf(InvalidCampaignStateException.class);
        assertThat(searched.withParallelism(8).parallelism()).isEqualTo(8);
    }

    @Test
    void parallelismChangesFromAnyStateThatIsNotOver() {
        assertThat(queued.withParallelism(2).parallelism()).isEqualTo(2);
        assertThat(queued.start(NOW).pause().withParallelism(9).parallelism()).isEqualTo(9);
        assertThatThrownBy(() -> queued.withParallelism(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyARunningCampaignSpendsBudget() {
        var running = queued.start(NOW).spend(30);

        assertThat(running.spentSeconds()).isEqualTo(30);
        assertThat(running.remainingSeconds()).isEqualTo(CampaignDefinition.DEFAULT_BUDGET_SECONDS - 30);
        assertThat(running.budgetSpent()).isFalse();
        var paused = running.pause();
        assertThatThrownBy(() -> paused.spend(1)).isInstanceOf(InvalidCampaignStateException.class);
        assertThat(running.spend(CampaignDefinition.DEFAULT_BUDGET_SECONDS).budgetSpent())
                .isTrue();
        assertThat(running.spend(CampaignDefinition.DEFAULT_BUDGET_SECONDS).remainingSeconds())
                .isZero();
    }

    @Test
    void aCampaignFailsFromAnyStateThatIsNotOver() {
        var failed = queued.start(NOW)
                .fail(new CampaignFailure(CampaignFailure.CANDIDATE_GENERATION_EXHAUSTED, "too small"), NOW);

        assertThat(failed.state()).isEqualTo(CampaignState.FAILED);
        assertThat(failed.failure().code()).isEqualTo(CampaignFailure.CANDIDATE_GENERATION_EXHAUSTED);
        assertThat(failed.finishedAt()).isEqualTo(NOW);
    }

    @Test
    void theDefinitionRefusesWhatTheContractRefuses() {
        assertThatThrownBy(() -> new SearchAlgorithm(1, 2, 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchAlgorithm(2, 1, 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchAlgorithm(2, 2, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchAlgorithm(2, 2, 2, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void seedsGrowByEtaEveryRound() {
        var algorithm = new SearchAlgorithm(32, 2, 2, 1);

        assertThat(algorithm.seedsInRound(0)).isEqualTo(2);
        assertThat(algorithm.seedsInRound(1)).isEqualTo(4);
        assertThat(algorithm.seedsInRound(2)).isEqualTo(8);
        assertThat(new SearchAlgorithm(32, 3, 3, 1).seedsInRound(2)).isEqualTo(27);
    }

    @Test
    void aSearchedCampaignStartsConfirmingAndCompletesWithItsEvaluations() {
        var searched = queued.start(NOW).completeSearch(List.of("c-0000"));
        var baseline = new Confirmation.Run("baseline", new UUID(2, 1), new UUID(3, 1), null);
        var candidate = new Confirmation.Run("c-0000", new UUID(2, 2), new UUID(3, 2), null);
        var confirming = searched.startConfirmation(new Confirmation(100, List.of(baseline, candidate)));

        assertThat(confirming.state()).isEqualTo(CampaignState.CONFIRMING);
        assertThat(confirming.confirmation().evaluated()).isFalse();
        assertThat(confirming.finishedAt()).isNull();

        var evaluated = confirming
                .confirmation()
                .evaluatedAs(java.util.Map.of(
                        "baseline", new Confirmation.Evaluation("UNCONFIRMED", 2.5),
                        "c-0000", new Confirmation.Evaluation("CONFIRMED", 1.5)));
        var unevaluated = confirming.confirmation();
        assertThatThrownBy(() -> confirming.completeConfirmation(unevaluated, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        var completed = confirming.completeConfirmation(evaluated, NOW);

        assertThat(completed.state()).isEqualTo(CampaignState.COMPLETED);
        assertThat(completed.finishedAt()).isEqualTo(NOW);
        assertThat(completed.confirmation().runs())
                .extracting(run -> run.subject() + ":" + run.evaluation().verdict())
                .containsExactly("baseline:UNCONFIRMED", "c-0000:CONFIRMED");
    }

    @Test
    void confirmationStartsOnlyFromACompletedSearchAndCompletesOnlyWhenConfirming() {
        var confirmation = new Confirmation(100, List.of());

        assertThatThrownBy(() -> queued.startConfirmation(confirmation))
                .isInstanceOf(InvalidCampaignStateException.class);
        var searched = queued.start(NOW).completeSearch(List.of());
        assertThatThrownBy(() -> searched.completeConfirmation(confirmation, NOW))
                .isInstanceOf(InvalidCampaignStateException.class);
    }

    @Test
    void aConfirmingCampaignCanBeCancelledOrFailedButNotPaused() {
        var confirming =
                queued.start(NOW).completeSearch(List.of("c-0000")).startConfirmation(new Confirmation(100, List.of()));

        assertThat(confirming.cancel(NOW).state()).isEqualTo(CampaignState.CANCELLED);
        assertThat(confirming.fail(new CampaignFailure("X", "failed"), NOW).state())
                .isEqualTo(CampaignState.FAILED);
        assertThatThrownBy(confirming::pause).isInstanceOf(InvalidCampaignStateException.class);
    }

    private Campaign in(CampaignState state) {
        var running = queued.start(NOW);
        return switch (state) {
            case COMPLETED ->
                new Campaign(
                        running.campaignId(),
                        running.definition(),
                        running.parallelism(),
                        CampaignState.COMPLETED,
                        0,
                        0,
                        List.of(),
                        null,
                        null,
                        NOW,
                        NOW,
                        NOW);
            case FAILED -> running.fail(new CampaignFailure("X", "failed"), NOW);
            default -> throw new IllegalArgumentException(state.name());
        };
    }
}
