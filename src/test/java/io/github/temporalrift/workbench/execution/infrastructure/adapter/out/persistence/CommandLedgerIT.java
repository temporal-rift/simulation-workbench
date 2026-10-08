package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.command.CommandIntent;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

@WorkbenchIntegrationTest
class CommandLedgerIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ATTEMPT = new UUID(0, 1);

    @Autowired
    private CommandLedger ledger;

    @Autowired
    private CreateExperimentUseCase createExperiment;

    @Autowired
    private StartRunUseCase startRun;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID caseId;

    @BeforeEach
    void aCaseToSpendDecisionsOn() {
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        var experiment = createExperiment
                .handle(new CreateExperimentUseCase.Command(
                        UUID.randomUUID(), ExperimentManifests.threePlayerSingleSet()))
                .experimentId();
        var runId = startRun.handle(new StartRunUseCase.Command(experiment, UUID.randomUUID()))
                .run()
                .runId();
        caseId = jdbc.queryForObject(
                "SELECT case_id FROM run_case WHERE run_id = ? ORDER BY ordinal LIMIT 1", UUID.class, runId);
    }

    @Test
    void aFreeSlotIsSentOnceAndThenIsInDoubtUntilItIsResolved() {
        var slot = slot(0, "era1/hand-selection");

        assertThat(ledger.begin(slot, ATTEMPT, "keep", NOW)).isInstanceOf(CommandIntent.Send.class);
        assertThat(ledger.begin(slot, ATTEMPT, "keep", NOW)).isInstanceOf(CommandIntent.InDoubt.class);
        assertThat(ledger.inDoubt(caseId))
                .singleElement()
                .satisfies(held -> assertThat(held.request()).isEqualTo("keep"));

        ledger.resolve(slot, SlotStatus.ACCEPTED, "ACCEPTED", NOW);

        assertThat(ledger.begin(slot, ATTEMPT, "keep", NOW)).isInstanceOf(CommandIntent.AlreadyAccepted.class);
        assertThat(ledger.inDoubt(caseId)).isEmpty();
    }

    @Test
    void aSlotThatWasDefinitivelyNotSpentIsReleasedForANewCommand() {
        var slot = slot(1, "era1/round1/action");
        ledger.begin(slot, ATTEMPT, "first", NOW);
        ledger.resolve(slot, SlotStatus.NOT_SPENT, "422-03", NOW);

        assertThat(ledger.begin(slot, new UUID(0, 2), "second", NOW)).isInstanceOf(CommandIntent.Send.class);

        var held = ledger.find(slot).orElseThrow();
        assertThat(held.status()).isEqualTo(SlotStatus.SENT);
        assertThat(held.request()).isEqualTo("second");
        assertThat(held.attemptId()).isEqualTo(new UUID(0, 2));
        assertThat(held.outcome()).isNull();
    }

    @Test
    void theTranscriptIsTheAcceptedSlotsInCanonicalOrderWhateverAttemptSentThem() {
        accept(2, "era1/round2/action", "b");
        accept(0, "era1/round1/action", "a");
        accept(1, "era1/hand-selection", "c");
        accept(0, "era1/hand-selection", "d");
        ledger.begin(slot(2, "era1/paradox-resolution"), ATTEMPT, "pending", NOW);

        assertThat(ledger.accepted(caseId))
                .extracting(held -> held.id().windowKey() + "#" + held.id().seatIndex())
                .containsExactly(
                        "era1/hand-selection#0",
                        "era1/hand-selection#1",
                        "era1/round1/action#0",
                        "era1/round2/action#2");
    }

    @Test
    void resettingACaseForgetsItsSlotsAndOnlyThose() {
        accept(0, "era1/hand-selection", "kept");
        var otherCase =
                jdbc.queryForObject("SELECT case_id FROM run_case WHERE case_id <> ? LIMIT 1", UUID.class, caseId);
        var other = new SlotId(otherCase, 0, "era1/hand-selection");
        ledger.begin(other, ATTEMPT, "elsewhere", NOW);

        ledger.reset(caseId);

        assertThat(ledger.accepted(caseId)).isEmpty();
        assertThat(ledger.find(other)).isPresent();
        assertThat(ledger.begin(slot(0, "era1/hand-selection"), ATTEMPT, "fresh game", NOW))
                .isInstanceOf(CommandIntent.Send.class);
    }

    @Test
    void racingAttemptsCanOnlyOneSendTheSameSlot() throws Exception {
        var slot = slot(0, "era1/hand-selection");
        var gate = new CountDownLatch(1);
        var intents = new ArrayList<Future<CommandIntent>>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (var racer = 0; racer < 6; racer++) {
                var attempt = new UUID(0, 100 + racer);
                intents.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    return ledger.begin(slot, attempt, "keep", NOW);
                }));
            }
            gate.countDown();
            List<CommandIntent> results = new ArrayList<>();
            for (var intent : intents) {
                results.add(intent.get(20, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(CommandIntent.Send.class::isInstance))
                    .hasSize(1);
            assertThat(results.stream().filter(CommandIntent.InDoubt.class::isInstance))
                    .hasSize(5);
        }
    }

    private void accept(int seat, String window, String request) {
        var slot = slot(seat, window);
        ledger.begin(slot, ATTEMPT, request, NOW);
        ledger.resolve(slot, SlotStatus.ACCEPTED, "ACCEPTED", NOW);
    }

    private SlotId slot(int seat, String window) {
        return new SlotId(caseId, seat, window);
    }
}
