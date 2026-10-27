package io.github.temporalrift.workbench.fidelity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunReproductionUseCase;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.application.port.in.AdvanceFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetSimulatorFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.ListFidelityCasesUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.StartFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCaseResult;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckActiveException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCounts;
import io.github.temporalrift.workbench.fidelity.domain.FidelityMatrix;
import io.github.temporalrift.workbench.fidelity.domain.FidelityVerdict;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.shared.game.EndReason;

/**
 * A whole check on the scripted lanes: {@code lane-0} stands for the simulator and {@code lane-1} for the real lane, so
 * the services' games are scripted, and what is exercised is the check itself: the experiment it freezes, the run, the
 * reproductions on the chosen lane, the records and the gate.
 */
@WorkbenchIntegrationTest
class FidelityCheckIT {

    private static final String WORKER = "fidelity-it";
    private static final int STEP_LIMIT = 100_000;

    /** The bundle of the test baseline rules document, which the variants' overrides are applied to. */
    private static final BundleDigests BASELINE =
            new BundleDigests(VariantRulesFixtures.baseline().digest(), "b".repeat(64));

    @Autowired
    private StartFidelityCheckUseCase startCheck;

    @Autowired
    private AdvanceFidelityUseCase fidelity;

    @Autowired
    private GetFidelityCheckUseCase getCheck;

    @Autowired
    private ListFidelityCasesUseCase listCases;

    @Autowired
    private GetSimulatorFidelityUseCase simulatorFidelity;

    @Autowired
    private RunBatchUseCase batch;

    @Autowired
    private RunReproductionUseCase reproductions;

    @Autowired
    private ScriptedLanes lanes;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAndServeTheBaseline() {
        jdbc.update("DELETE FROM fidelity_case");
        jdbc.update("DELETE FROM fidelity_check");
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        lanes.reset();
        lanes.serve(BASELINE, BASELINE);
    }

    private FidelityCheckView accepted() {
        return startCheck.handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), "42"));
    }

    /** Moves the check and the execution workers along until {@code done} holds. */
    private void driveUntil(BooleanSupplier done) {
        for (var step = 0; step < STEP_LIMIT && !done.getAsBoolean(); step++) {
            batch.maintain();
            var advanced = fidelity.advance();
            var played = batch.runNextCase(WORKER);
            var reproduced = reproductions.runNext(WORKER);
            if (!advanced && !played && !reproduced) {
                break;
            }
        }
        assertThat(done.getAsBoolean())
                .as("the check reached the awaited point: %s", describe())
                .isTrue();
    }

    private String describe() {
        return jdbc.queryForList("SELECT state, failure_code, failure_message FROM fidelity_check")
                + " runs "
                + jdbc.queryForList("SELECT state, count(*) FROM run_case GROUP BY state")
                + " reproductions "
                + jdbc.queryForList("SELECT state, count(*) FROM reproduction GROUP BY state")
                + " cases "
                + jdbc.queryForList("SELECT result, count(*) FROM fidelity_case GROUP BY result")
                + " fits "
                + jdbc.queryForList("SELECT DISTINCT lane_fit FROM run_case")
                + lanes.freeLanes();
    }

    private boolean finished(UUID checkId) {
        return !getCheck.handle(checkId).check().state().isOpen();
    }

    private boolean hasCases(UUID checkId) {
        return getCheck.handle(checkId).counts().total() > 0;
    }

    /** One full check serves several assertions: playing and reproducing 660 games takes minutes. */
    @Test
    void aCheckPlaysTheFullMatrixAndPassesWhenEveryCaseMatches() {
        assertThat(simulatorFidelity.handle().established()).isFalse();
        var check = accepted();
        assertTheCheckWasAcceptedWithTheThreeVariants(check);

        driveUntil(() -> finished(check.check().id()));

        var done = getCheck.handle(check.check().id());
        assertTheCheckPassedOnEveryCase(done);
        assertEveryCaseWasReproducedOnceOnTheChosenLane(done);
        assertTheCasesAreTheMatrixInOrder(done);
        assertTheSimulatorIsEstablishedForItsRevisions(done);
    }

    private static void assertTheCheckWasAcceptedWithTheThreeVariants(FidelityCheckView check) {
        assertThat(check.check().state()).isEqualTo(FidelityCheckState.QUEUED);
        assertThat(check.check().laneId()).isEqualTo("lane-1");
        assertThat(check.check().variants())
                .extracting("label")
                .containsExactly(VariantLabel.BASELINE, VariantLabel.LOW, VariantLabel.HIGH);
    }

    private static void assertTheCheckPassedOnEveryCase(FidelityCheckView done) {
        assertThat(done.check().state()).isEqualTo(FidelityCheckState.PASSED);
        assertThat(done.check().verdict()).isEqualTo(FidelityVerdict.PASSED);
        assertThat(done.check().failure()).isNull();
        assertThat(done.counts()).isEqualTo(new FidelityCounts(330, 330, 0, 0, 0));
        assertThat(done.counts().total()).isEqualTo(FidelityMatrix.caseCount());
        assertThat(done.check().startedAt()).isNotNull();
        assertThat(done.check().finishedAt()).isNotNull();
    }

    private void assertEveryCaseWasReproducedOnceOnTheChosenLane(FidelityCheckView done) {
        var runId = done.check().runId();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reproduction WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(330);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reproduction WHERE run_id = ? AND lane_id = 'lane-1'",
                        Integer.class,
                        runId))
                .isEqualTo(330);
        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT case_id) FROM reproduction WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(330);
    }

    private void assertTheCasesAreTheMatrixInOrder(FidelityCheckView done) {
        var cases = listCases
                .handle(new ListFidelityCasesUseCase.Query(done.check().id(), null, null, 500, 0))
                .items();

        assertThat(cases).hasSize(330);
        assertThat(cases).isSortedAccordingTo(FidelityMatrix.ORDER);
        assertThat(cases)
                .extracting("ordinal")
                .containsExactlyElementsOf(IntStream.range(0, 330).boxed().toList());
        assertThat(cases.stream().map(FidelityCase::variant).distinct())
                .containsExactly(VariantLabel.BASELINE, VariantLabel.LOW, VariantLabel.HIGH);
        assertThat(cases.stream().map(FidelityCase::policyId).distinct())
                .containsExactlyInAnyOrder("faction-greedy", "random");
        assertThat(cases).allSatisfy(c -> assertThat(c.result()).isEqualTo(FidelityCaseResult.MATCH));
    }

    private void assertTheSimulatorIsEstablishedForItsRevisions(FidelityCheckView done) {
        var gate = simulatorFidelity.handle();

        assertThat(gate.established()).isTrue();
        assertThat(gate.passingCheck().check().id()).isEqualTo(done.check().id());
        assertThat(gate.sourceRevisions()).isEqualTo(done.check().simulator().sourceRevisions());
    }

    @Test
    void aSecondCheckIsRefusedWhileTheFirstIsOpen() {
        accepted();

        assertThatThrownBy(this::accepted).isInstanceOf(FidelityCheckActiveException.class);
    }

    @Test
    void aCaseThatPlaysDifferentlyOnTheRealLaneFailsTheCheckWithNoTolerance() {
        var check = accepted();
        driveUntil(() -> hasCases(check.check().id()));

        // From here on the services play another game: what a reordered due timer would do to a real build.
        lanes.script(context -> ScriptedLanes.withoutWinners(context, EndReason.TIMELINE_COLLAPSED));
        driveUntil(() -> finished(check.check().id()));

        var done = getCheck.handle(check.check().id());
        assertThat(done.check().state()).isEqualTo(FidelityCheckState.FAILED);
        assertThat(done.check().verdict()).isEqualTo(FidelityVerdict.FAILED);
        assertThat(done.counts().diverged()).isPositive();
        assertThat(done.counts().matched()).isLessThan(done.counts().total());
        var diverged = listCases
                .handle(new ListFidelityCasesUseCase.Query(check.check().id(), null, FidelityCaseResult.DIVERGED, 1, 0))
                .items();
        assertThat(diverged).isNotEmpty();
        assertThat(diverged.getFirst().firstDivergence().kind()).isEqualTo("END_REASON");
        assertThat(simulatorFidelity.handle().established()).isFalse();
    }
}
