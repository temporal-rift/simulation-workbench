package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.application.port.in.CaseView;
import io.github.temporalrift.workbench.execution.application.port.in.RunView;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.CaseCounts;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;

class RunApiMapperTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID CASE = new UUID(1, 1);
    private static final UUID RUN = new UUID(2, 2);

    @Test
    void aQueuedRunMapsEveryRequiredFieldIncludingTheNullOnes() {
        var run = Run.queued(RUN, new UUID(3, 3), T0);

        var api = RunApiMapper.toApi(new RunView(run, new CaseCounts(3, 3, 0, 0, 0, 0)));

        assertThat(api.getState().name()).isEqualTo("QUEUED");
        assertThat(api.getCounts().getRequested()).isEqualTo(3);
        assertThat(api.getCreatedAt().toInstant()).isEqualTo(T0);
        assertThat(api.getStartedAt()).isNull();
        assertThat(api.getFinishedAt()).isNull();
        assertThat(api.getFailure()).isNull();
    }

    @Test
    void aFailedRunCarriesItsFailureCode() {
        var failed = Run.queued(RUN, new UUID(3, 3), T0)
                .fail(T0.plusSeconds(5), new Failure(FailureCode.CONTRACT_MISMATCH, "unsupported"));

        var api = RunApiMapper.toApi(new RunView(failed, new CaseCounts(1, 1, 0, 0, 0, 0)));

        assertThat(api.getFailure().getCode()).isEqualTo("CONTRACT_MISMATCH");
        assertThat(api.getFinishedAt().toInstant()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void anAbandonedGameIsACaseResultWithItsEndingCauseAndNoWinners() {
        var result = new CaseResult(
                EndReason.ALL_PLAYERS_ABANDONED,
                List.of(),
                List.of(
                        new FinalScore(0, "ERASERS", 4),
                        new FinalScore(1, "WEAVERS", 9),
                        new FinalScore(2, "PROPHETS", 1)),
                2,
                4,
                9,
                "a".repeat(64));

        var api = RunApiMapper.toApi(caseView(CaseState.SUCCEEDED, result, successfulAttempt()));

        assertThat(api.getState().name()).isEqualTo("SUCCEEDED");
        assertThat(api.getResult().getEndReason().name()).isEqualTo("ALL_PLAYERS_ABANDONED");
        assertThat(api.getResult().getWinners()).isEmpty();
        assertThat(api.getResult().getFinalScores()).hasSize(3);
        assertThat(api.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getState().name()).isEqualTo("SUCCEEDED");
            assertThat(attempt.getFailure()).isNull();
        });
    }

    @Test
    void winnersOfSpecialEndingsMapWithoutAWinType() {
        var result = new CaseResult(
                EndReason.TIMELINE_COLLAPSED,
                List.of(new Winner(1, "WEAVERS", null), new Winner(2, "PROPHETS", null)),
                List.of(
                        new FinalScore(0, "ERASERS", 4),
                        new FinalScore(1, "WEAVERS", 9),
                        new FinalScore(2, "PROPHETS", 9)),
                5,
                12,
                30,
                "b".repeat(64));

        var api = RunApiMapper.toApi(caseView(CaseState.SUCCEEDED, result, successfulAttempt()));

        assertThat(api.getResult().getWinners())
                .allSatisfy(winner -> assertThat(winner.getWinType()).isNull());
        assertThat(api.getResult().getWinners().getFirst().getFaction().name()).isEqualTo("WEAVERS");
    }

    @Test
    void aNormalVictoryKeepsItsWinType() {
        var result = new CaseResult(
                EndReason.WIN_CONDITION_MET,
                List.of(new Winner(0, "ERASERS", WinType.FACTION_OBJECTIVE)),
                List.of(
                        new FinalScore(0, "ERASERS", 21),
                        new FinalScore(1, "WEAVERS", 9),
                        new FinalScore(2, "PROPHETS", 8)),
                4,
                10,
                25,
                "c".repeat(64));

        var api = RunApiMapper.toApi(caseView(CaseState.SUCCEEDED, result, successfulAttempt()));

        assertThat(api.getResult().getWinners().getFirst().getWinType().name()).isEqualTo("FACTION_OBJECTIVE");
    }

    @Test
    void aFailedAttemptIsListedWithItsReasonAndNoResult() {
        var failed = new Attempt(
                new UUID(4, 4),
                CASE,
                1,
                AttemptState.FAILED,
                new UUID(5, 5),
                "lane-1",
                T0,
                T0.plusSeconds(9),
                new Failure(FailureCode.RUNNER_TIMEOUT, "no ending in time"));

        var api = RunApiMapper.toApi(caseView(CaseState.FAILED, null, failed));

        assertThat(api.getResult()).isNull();
        assertThat(api.getAttempts().getFirst().getFailure().getCode()).isEqualTo("RUNNER_TIMEOUT");
        assertThat(api.getAttempts().getFirst().getGameId()).isEqualTo(new UUID(5, 5));
    }

    private static Attempt successfulAttempt() {
        return new Attempt(new UUID(4, 4), CASE, 1, AttemptState.SUCCEEDED, new UUID(5, 5), "lane-1", T0, T0, null);
    }

    private static CaseView caseView(CaseState state, CaseResult result, Attempt attempt) {
        var logicalCase = new LogicalCase(
                CASE,
                RUN,
                new UUID(6, 6),
                0,
                "baseline",
                "42",
                3,
                List.of(
                        new SeatPlan(0, "ERASERS", "random", "1.0.0"),
                        new SeatPlan(1, "WEAVERS", "random", "1.0.0"),
                        new SeatPlan(2, "PROPHETS", "random", "1.0.0")),
                state,
                result);
        return new CaseView(logicalCase, List.of(attempt));
    }
}
