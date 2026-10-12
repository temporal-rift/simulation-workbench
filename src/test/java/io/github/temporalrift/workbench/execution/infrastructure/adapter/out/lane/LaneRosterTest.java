package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.ManualResetScheduler;
import io.github.temporalrift.workbench.execution.support.RecordingResets;

class LaneRosterTest {

    private static final BundleDigests BASELINE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final UUID CASE = new UUID(1, 1);
    private static final UUID CASE_KEY = new UUID(2, 2);
    private static final UUID OTHER_CASE = new UUID(3, 3);

    private final RecordingResets resets = new RecordingResets();
    private final ManualResetScheduler scheduler = new ManualResetScheduler();

    @Test
    void aLaneWithoutAResetHookIsOfferedAgainImmediatelyAfterItsCase() {
        var roster = roster(false);
        var lane = roster.take(CASE, BASELINE, null).orElseThrow();
        lane.host(context());

        lane.giveBack();

        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(resets.calls()).isEmpty();
    }

    @Test
    void aLaneWithoutAResetHookKeepsNoGameForAnInterruptedAttempt() {
        var roster = roster(false);
        var lane = roster.take(CASE, BASELINE, null).orElseThrow();
        lane.host(context());
        lane.retainForRecovery();

        lane.giveBack();

        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(roster.heldCases()).isEmpty();
    }

    @Test
    void aResettableLaneIsResetBeforeItIsFirstOffered() {
        var roster = roster(true);

        assertThat(roster.free()).isEmpty();
        roster.prepare(List.of());
        assertThat(roster.free()).isEmpty();
        scheduler.runAll();

        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(resets.calls())
                .singleElement()
                .satisfies(call -> assertThat(call.caseKey()).isNull());
    }

    @Test
    void aResettableLaneIsResetAfterItsCaseBeforeItIsOfferedAgain() {
        var roster = prepared();
        var lane = roster.take(CASE, BASELINE, null).orElseThrow();
        lane.host(context());

        lane.giveBack();

        assertThat(roster.free()).isEmpty();
        assertThat(roster.take(OTHER_CASE, BASELINE, null)).isEmpty();
        scheduler.runAll();
        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(resets.calls()).singleElement().satisfies(call -> {
            assertThat(call.laneId()).isEqualTo("a");
            assertThat(call.caseKey()).isEqualTo(CASE_KEY);
        });
    }

    @Test
    void aFailingResetKeepsTheLaneUnavailableAndIsRetriedWithTheSameIdempotencyKey() {
        var roster = prepared();
        var lane = roster.take(CASE, BASELINE, null).orElseThrow();
        lane.host(context());
        resets.refuseNext(2);

        lane.giveBack();
        scheduler.runPending();
        assertThat(roster.free()).isEmpty();
        scheduler.runPending();
        assertThat(roster.free()).isEmpty();
        scheduler.runPending();

        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(resets.calls()).extracting(RecordingResets.Call::succeeded).containsExactly(false, false, true);
        assertThat(resets.calls())
                .extracting(RecordingResets.Call::idempotencyKey)
                .containsOnly(resets.calls().getFirst().idempotencyKey());
        assertThat(scheduler.delays()).containsExactly(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void aResetEndingInAnErrorIsRetried() {
        var broken = new AtomicBoolean(true);
        var roster = new LaneRoster(
                List.of(new LaneRoster.Member("a", BASELINE, true)),
                (_, _, _) -> {
                    if (broken.getAndSet(false)) {
                        throw new LinkageError("broken hook client");
                    }
                },
                scheduler);
        roster.prepare(List.of());

        assertThatThrownBy(scheduler::runPending).isInstanceOf(LinkageError.class);
        assertThat(roster.free()).isEmpty();
        scheduler.runPending();

        assertThat(roster.free()).containsOnlyKeys("a");
    }

    @Test
    void retriesBackOffUpToOneMinute() {
        var roster = prepared();
        var lane = roster.take(CASE, BASELINE, null).orElseThrow();
        lane.host(context());
        resets.refuseNext(9);

        lane.giveBack();
        scheduler.runAll();

        assertThat(scheduler.delays())
                .containsExactly(
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(16),
                        Duration.ofSeconds(32),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1));
        assertThat(roster.free()).containsOnlyKeys("a");
    }

    @Test
    void everyReleaseUsesAFreshIdempotencyKey() {
        var roster = prepared();
        for (var round = 0; round < 2; round++) {
            var lane = roster.take(CASE, BASELINE, null).orElseThrow();
            lane.host(context());
            lane.giveBack();
            scheduler.runAll();
        }

        assertThat(resets.calls())
                .extracting(RecordingResets.Call::idempotencyKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void aLaneThatHostedNoCaseNeedsNoReset() {
        var roster = prepared();

        roster.take(CASE, BASELINE, null).orElseThrow().giveBack();

        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(resets.calls()).isEmpty();
    }

    @Test
    void returningALaneTwiceReturnsItOnce() {
        var roster = prepared();
        var lane = roster.take(CASE, BASELINE, null).orElseThrow();
        lane.host(context());

        lane.giveBack();
        lane.giveBack();
        scheduler.runAll();

        assertThat(resets.calls()).hasSize(1);
    }

    @Test
    void anInterruptedAttemptKeepsItsLaneForItsCaseAlone() {
        var roster = prepared();
        var lane = roster.take(CASE, BASELINE, null).orElseThrow();
        lane.host(context());
        lane.retainForRecovery();

        lane.giveBack();

        assertThat(resets.calls()).isEmpty();
        assertThat(roster.free()).isEmpty();
        assertThat(roster.heldCases()).containsExactly(CASE);
        assertThat(roster.take(OTHER_CASE, BASELINE, null)).isEmpty();
        assertThat(roster.takeExactly("a", BASELINE)).isEmpty();
        var recovering = roster.take(CASE, BASELINE, null).orElseThrow();
        assertThat(recovering.laneId()).isEqualTo("a");
        assertThat(roster.heldCases()).isEmpty();

        recovering.host(context());
        recovering.giveBack();
        scheduler.runAll();

        assertThat(resets.calls()).hasSize(1);
        assertThat(roster.free()).containsOnlyKeys("a");
    }

    @Test
    void aHeldLaneIsResetWhenItsCaseNoLongerNeedsIt() {
        var roster = prepared();
        var lane = roster.take(CASE, BASELINE, null).orElseThrow();
        lane.host(context());
        lane.retainForRecovery();
        lane.giveBack();

        roster.releaseHeld(OTHER_CASE);
        assertThat(roster.heldCases()).containsExactly(CASE);
        roster.releaseHeld(CASE);
        scheduler.runAll();

        assertThat(roster.heldCases()).isEmpty();
        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(resets.calls())
                .singleElement()
                .satisfies(call -> assertThat(call.caseKey()).isEqualTo(CASE_KEY));
    }

    @Test
    void preparationHoldsTheLanesOfInterruptedAttemptsAndResetsTheOthers() {
        var roster = new LaneRoster(
                List.of(new LaneRoster.Member("a", BASELINE, true), new LaneRoster.Member("b", BASELINE, true)),
                resets,
                scheduler);

        roster.prepare(List.of(new LaneProvider.Hold("a", CASE, CASE_KEY)));
        scheduler.runAll();

        assertThat(roster.heldCases()).containsExactly(CASE);
        assertThat(roster.free()).containsOnlyKeys("b");
        assertThat(resets.calls()).extracting(RecordingResets.Call::laneId).containsExactly("b");
    }

    @Test
    void aCaseWithAHeldLaneIsGivenThatLaneBeforeAFreeOne() {
        var roster = new LaneRoster(
                List.of(new LaneRoster.Member("a", BASELINE, true), new LaneRoster.Member("b", BASELINE, true)),
                resets,
                scheduler);
        roster.prepare(List.of(new LaneProvider.Hold("b", CASE, CASE_KEY)));
        scheduler.runAll();

        assertThat(roster.take(CASE, BASELINE, null).orElseThrow().laneId()).isEqualTo("b");
    }

    @Test
    void preparationLeavesLanesThatAreAlreadyInServiceAlone() {
        var roster = prepared();
        var lane = roster.take(CASE, BASELINE, null).orElseThrow();

        roster.prepare(List.of());

        assertThat(roster.free()).isEmpty();
        assertThat(resets.calls()).isEmpty();
        lane.giveBack();
        assertThat(roster.free()).containsOnlyKeys("a");
    }

    private LaneRoster roster(boolean resettable) {
        return new LaneRoster(List.of(new LaneRoster.Member("a", BASELINE, resettable)), resets, scheduler);
    }

    /** A resettable lane past its start reset, which the recorded calls and delays leave out. */
    private LaneRoster prepared() {
        var roster = roster(true);
        roster.prepare(List.of());
        scheduler.runAll();
        resets.clear();
        scheduler.clear();
        return roster;
    }

    private static CaseLane.CaseContext context() {
        return new CaseLane.CaseContext(
                CASE, new UUID(4, 4), CASE_KEY, "42", "d", List.of(), Instant.parse("2026-01-01T00:00:00Z"));
    }
}
