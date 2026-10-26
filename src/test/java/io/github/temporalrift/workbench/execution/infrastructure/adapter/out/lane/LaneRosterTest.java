package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.support.ManualResetScheduler;
import io.github.temporalrift.workbench.execution.support.Provenances;
import io.github.temporalrift.workbench.execution.support.RecordingContexts;
import io.github.temporalrift.workbench.execution.support.RecordingResets;

class LaneRosterTest {

    private static final BundleDigests BASELINE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final LaneFit FIT = Provenances.fitOf(BASELINE);
    private static final LaneFit OTHER_FIT = Provenances.fitOf(new BundleDigests("a".repeat(64), "c".repeat(64)));
    private static final UUID CASE = new UUID(1, 1);
    private static final UUID CASE_KEY = new UUID(2, 2);

    private final RecordingContexts contexts = new RecordingContexts();
    private final RecordingResets resets = new RecordingResets();
    private final ManualResetScheduler scheduler = new ManualResetScheduler();

    @Test
    void aLaneIsOfferedOnlyOncePrepared() {
        var roster = roster(false);

        assertThat(roster.free()).isEmpty();
        roster.prepare();

        assertThat(roster.free()).containsOnlyKeys("a");
    }

    @Test
    void preparationClosesWhateverContextIsOpenOnTheLane() {
        var roster = roster(false);

        roster.prepare();

        assertThat(contexts.calls()).singleElement().satisfies(call -> {
            assertThat(call.laneId()).isEqualTo("a");
            assertThat(call.caseKey()).isNull();
        });
        assertThat(resets.calls()).isEmpty();
    }

    @Test
    void aLaneIsMatchedByWhatItFitsNotByItsRules() {
        var roster = prepared(false);

        assertThat(roster.take(OTHER_FIT, null)).isEmpty();
        assertThat(roster.takeExactly("a", OTHER_FIT)).isEmpty();
        assertThat(roster.take(FIT, null)).isPresent();
    }

    @Test
    void theContextOfACaseIsClosedOnBothServicesBeforeItsLaneIsOfferedAgain() {
        var roster = prepared(false);
        var lane = hosted(roster);

        lane.giveBack();

        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(contexts.calls()).singleElement().satisfies(call -> {
            assertThat(call.laneId()).isEqualTo("a");
            assertThat(call.caseKey()).isEqualTo(CASE_KEY);
            assertThat(call.services()).containsExactlyInAnyOrder(LaneService.GAME, LaneService.TIMELINE);
        });
        assertThat(resets.calls()).isEmpty();
    }

    @Test
    void aServiceThatRefusedTheContextIsNotAskedToCloseIt() {
        var roster = prepared(false);
        var lane = roster.take(FIT, null).orElseThrow();
        lane.host(context());
        lane.opened(LaneService.GAME);
        lane.opened(LaneService.TIMELINE);
        lane.refused(LaneService.TIMELINE);

        lane.giveBack();

        assertThat(contexts.calls())
                .singleElement()
                .satisfies(call -> assertThat(call.services()).containsExactly(LaneService.GAME));
    }

    @Test
    void aLaneThatHostedNoContextNeedsNoClose() {
        var roster = prepared(false);

        roster.take(FIT, null).orElseThrow().giveBack();

        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(contexts.calls()).isEmpty();
    }

    @Test
    void returningALaneTwiceClosesItOnce() {
        var roster = prepared(false);
        var lane = hosted(roster);

        lane.giveBack();
        lane.giveBack();

        assertThat(contexts.calls()).hasSize(1);
    }

    @Test
    void aFailedCloseFallsBackToTheResetHookBeforeTheLaneIsOfferedAgain() {
        var roster = prepared(true);
        var lane = hosted(roster);
        contexts.refuseNext(1);

        lane.giveBack();

        assertThat(roster.free()).isEmpty();
        assertThat(roster.take(FIT, null)).isEmpty();
        scheduler.runAll();
        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(resets.calls()).singleElement().satisfies(call -> {
            assertThat(call.laneId()).isEqualTo("a");
            assertThat(call.caseKey()).isEqualTo(CASE_KEY);
        });
    }

    @Test
    void aFailingResetKeepsTheLaneUnavailableAndIsRetriedWithTheSameIdempotencyKey() {
        var roster = prepared(true);
        var lane = hosted(roster);
        contexts.refuseNext(1);
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
    void aLaneWithoutAResetHookRetriesTheCloseUntilItSucceeds() {
        var roster = prepared(false);
        var lane = hosted(roster);
        contexts.refuseNext(2);

        lane.giveBack();
        assertThat(roster.free()).isEmpty();
        scheduler.runAll();

        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(contexts.calls())
                .extracting(RecordingContexts.Call::succeeded)
                .containsExactly(false, false, true);
        assertThat(resets.calls()).isEmpty();
    }

    @Test
    void aResetEndingInAnErrorIsRetried() {
        var broken = new AtomicBoolean(true);
        var roster = new LaneRoster(
                List.of(new LaneRoster.Member("a", FIT, true)),
                contexts,
                (_, _, _) -> {
                    if (broken.getAndSet(false)) {
                        throw new LinkageError("broken hook client");
                    }
                },
                scheduler);
        roster.prepare();
        var lane = hosted(roster);
        contexts.refuseNext(1);
        lane.giveBack();

        assertThatThrownBy(scheduler::runPending).isInstanceOf(LinkageError.class);
        assertThat(roster.free()).isEmpty();
        scheduler.runPending();

        assertThat(roster.free()).containsOnlyKeys("a");
    }

    @Test
    void retriesBackOffUpToOneMinute() {
        var roster = prepared(true);
        var lane = hosted(roster);
        contexts.refuseNext(1);
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
    void everyRecoveryUsesAFreshIdempotencyKey() {
        var roster = prepared(true);
        for (var round = 0; round < 2; round++) {
            var lane = hosted(roster);
            contexts.refuseNext(1);
            lane.giveBack();
            scheduler.runAll();
        }

        assertThat(resets.calls())
                .extracting(RecordingResets.Call::idempotencyKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void aFailedCloseOnPreparationFallsBackToTheResetHook() {
        var roster = roster(true);
        contexts.refuseNext(1);

        roster.prepare();
        assertThat(roster.free()).isEmpty();
        scheduler.runAll();

        assertThat(roster.free()).containsOnlyKeys("a");
        assertThat(resets.calls())
                .singleElement()
                .satisfies(call -> assertThat(call.caseKey()).isNull());
    }

    @Test
    void preparationLeavesLanesThatAreAlreadyInServiceAlone() {
        var roster = prepared(false);
        var lane = roster.take(FIT, null).orElseThrow();

        roster.prepare();

        assertThat(roster.free()).isEmpty();
        assertThat(contexts.calls()).isEmpty();
        lane.giveBack();
        assertThat(roster.free()).containsOnlyKeys("a");
    }

    private LaneRoster roster(boolean resettable) {
        return new LaneRoster(List.of(new LaneRoster.Member("a", FIT, resettable)), contexts, resets, scheduler);
    }

    /** A lane past its start preparation, which the recorded calls and delays leave out. */
    private LaneRoster prepared(boolean resettable) {
        var roster = roster(resettable);
        roster.prepare();
        scheduler.runAll();
        contexts.clear();
        resets.clear();
        scheduler.clear();
        return roster;
    }

    private static LaneRoster.Tenancy hosted(LaneRoster roster) {
        var lane = roster.take(FIT, null).orElseThrow();
        lane.host(context());
        lane.opened(LaneService.GAME);
        lane.opened(LaneService.TIMELINE);
        return lane;
    }

    private static CaseLane.CaseContext context() {
        return new CaseLane.CaseContext(
                CASE,
                new UUID(4, 4),
                CASE_KEY,
                "42",
                "d",
                new CaseRules(new java.util.TreeMap<>(), "e".repeat(64)),
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
