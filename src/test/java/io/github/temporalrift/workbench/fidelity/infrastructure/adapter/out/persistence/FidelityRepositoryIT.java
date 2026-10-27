package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.fidelity.domain.CheckCreation;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCaseResult;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState;
import io.github.temporalrift.workbench.fidelity.domain.FidelityFailure;
import io.github.temporalrift.workbench.fidelity.domain.FidelityMatrix;
import io.github.temporalrift.workbench.fidelity.domain.FidelityVariant;
import io.github.temporalrift.workbench.fidelity.domain.FirstDivergence;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;
import io.github.temporalrift.workbench.fidelity.support.FidelityFixtures;

@WorkbenchIntegrationTest
class FidelityRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private FidelityRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM fidelity_case");
        jdbc.update("DELETE FROM fidelity_check");
    }

    private static FidelityCheck queued(String revision) {
        return FidelityCheck.queued(
                UUID.randomUUID(),
                "42",
                FidelityFixtures.environment(FidelityFixtures.SIMULATOR_IMAGE, revision),
                FidelityFixtures.environment(FidelityFixtures.LANE_IMAGE, revision),
                "lane-1",
                List.of(
                        new FidelityVariant(VariantLabel.BASELINE, "a".repeat(64), new TreeMap<>()),
                        new FidelityVariant(
                                VariantLabel.LOW,
                                "b".repeat(64),
                                new TreeMap<>(Map.of(
                                        "game.rules.win-score-threshold", new BigDecimal("16"),
                                        "game.rules.probability.amplify-multiplier.I", new BigDecimal("1.25"))))),
                NOW);
    }

    private static List<FidelityCase> cases(int count) {
        var set = FidelityMatrix.factionSets().getFirst();
        var cases = new ArrayList<FidelityCase>();
        for (var ordinal = 0; ordinal < count; ordinal++) {
            cases.add(FidelityCase.pending(
                    ordinal,
                    UUID.randomUUID(),
                    ordinal % 2 == 0 ? VariantLabel.BASELINE : VariantLabel.LOW,
                    3,
                    set,
                    ordinal % 3,
                    "random",
                    UUID.randomUUID()));
        }
        return cases;
    }

    private FidelityCheck stored(FidelityCheck check) {
        assertThat(repository.create(UUID.randomUUID(), "hash", check)).isInstanceOf(CheckCreation.Created.class);
        return check;
    }

    @Test
    void aCheckRoundTripsWithItsEnvironmentsAndVariants() {
        var check = stored(queued("1111111"));

        var read = repository.find(check.id()).orElseThrow();

        assertThat(read).isEqualTo(check);
        assertThat(read.variants().get(1).overrides())
                .containsEntry("game.rules.probability.amplify-multiplier.I", new BigDecimal("1.25"));
        assertThat(read.simulator().contracts()).containsEntry("session-api", "3.0.0");
        assertThat(repository.open()).contains(check);
    }

    @Test
    void theSameKeyReturnsTheOriginalClaim() {
        var key = UUID.randomUUID();
        var first = queued("1111111");
        repository.create(key, "hash-a", first);

        var second = repository.create(key, "hash-b", queued("1111111"));

        assertThat(second).isInstanceOfSatisfying(CheckCreation.Existing.class, existing -> {
            assertThat(existing.claim().check().id()).isEqualTo(first.id());
            assertThat(existing.claim().requestHash()).isEqualTo("hash-a");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fidelity_check", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aSecondOpenCheckIsRefusedByTheApplicationAndByTheDatabase() {
        stored(queued("1111111"));

        assertThat(repository.create(UUID.randomUUID(), "hash", queued("1111111")))
                .isInstanceOf(CheckCreation.Active.class);
        var entity = org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "INSERT INTO fidelity_check (check_id, idempotency_key, request_hash, seed, state, lane_id,"
                        + " simulator_json, lane_json, variants_json, game_revision, timeline_revision,"
                        + " read_revision, created_at) VALUES (?, ?, 'h', '1', 'RUNNING', 'lane-1', '{}', '{}',"
                        + " '[]', 'a', 'b', 'c', now())",
                UUID.randomUUID(),
                UUID.randomUUID()));
        assertThat(entity).hasMessageContaining("uq_fidelity_check_open");
    }

    @Test
    void aFinishedCheckLeavesRoomForTheNext() {
        var first = stored(queued("1111111"));
        var running = first.begun(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThat(repository.update(running, FidelityCheckState.QUEUED)).isTrue();
        assertThat(repository.update(running.failedByCases(NOW.plusSeconds(1)), FidelityCheckState.RUNNING))
                .isTrue();

        assertThat(repository.open()).isEmpty();
        assertThat(repository.create(UUID.randomUUID(), "hash", queued("1111111")))
                .isInstanceOf(CheckCreation.Created.class);
    }

    @Test
    void anUpdateOnlyAppliesToTheStateItExpects() {
        var check = stored(queued("1111111"));
        var running = check.begun(UUID.randomUUID(), UUID.randomUUID(), NOW);

        assertThat(repository.update(running, FidelityCheckState.RUNNING)).isFalse();
        assertThat(repository.update(running, FidelityCheckState.QUEUED)).isTrue();
        assertThat(repository.update(running, FidelityCheckState.QUEUED)).isFalse();
        assertThat(repository.find(check.id()).orElseThrow().state()).isEqualTo(FidelityCheckState.RUNNING);
    }

    @Test
    void aFailureIsStoredWithTheFinishedCheck() {
        var check = stored(queued("1111111"));

        repository.update(
                check.failed(new FidelityFailure("RUN_FAILED", "lane crashed"), NOW), FidelityCheckState.QUEUED);

        var read = repository.find(check.id()).orElseThrow();
        assertThat(read.failure()).isEqualTo(new FidelityFailure("RUN_FAILED", "lane crashed"));
        assertThat(read.finishedAt()).isEqualTo(NOW);
    }

    @Test
    void checksAreListedNewestFirstAndFilteredByState() {
        var older = stored(queued("1111111"));
        repository.update(older.failed(new FidelityFailure("X", "y"), NOW), FidelityCheckState.QUEUED);
        var newer = FidelityCheck.queued(
                UUID.randomUUID(), "43", older.simulator(), older.lane(), "lane-1", List.of(), NOW.plusSeconds(5));
        stored(newer);

        assertThat(repository.list(null, 10, 0).items())
                .extracting(FidelityCheck::id)
                .containsExactly(newer.id(), older.id());
        assertThat(repository.list(FidelityCheckState.FAILED, 10, 0).items())
                .extracting(FidelityCheck::id)
                .containsExactly(older.id());
        assertThat(repository.list(null, 1, 1).items())
                .extracting(FidelityCheck::id)
                .containsExactly(older.id());
        assertThat(repository.list(null, 1, 1).total()).isEqualTo(2);
    }

    @Test
    void theNewestPassedCheckIsFoundOnlyForExactlyItsRevisions() {
        var check = stored(queued("1111111"));
        var running = check.begun(UUID.randomUUID(), UUID.randomUUID(), NOW);
        repository.update(running, FidelityCheckState.QUEUED);
        repository.update(running.passed(NOW.plusSeconds(1)), FidelityCheckState.RUNNING);

        var same = check.simulator().sourceRevisions();
        assertThat(repository.newestPassed(same)).map(FidelityCheck::id).contains(check.id());
        assertThat(repository.newestPassed(new io.github.temporalrift.workbench.fidelity.domain.SourceRevisions(
                        "1111111", "1111111", "2222222")))
                .isEmpty();
    }

    @Test
    void casesRoundTripInMatrixOrderWithTheirResults() {
        var check = stored(queued("1111111"));
        repository.insertCases(check.id(), cases(6));

        assertThat(repository.caseCount(check.id())).isEqualTo(6);
        assertThat(repository.counts(check.id()).pending()).isEqualTo(6);
        var all = repository.cases(check.id(), null, null, 10, 0);
        assertThat(all.total()).isEqualTo(6);
        assertThat(all.items()).extracting(FidelityCase::ordinal).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(all.items().getFirst().factionSet())
                .isEqualTo(FidelityMatrix.factionSets().getFirst());
    }

    @Test
    void casesAreStoredOnlyOnce() {
        var check = stored(queued("1111111"));
        repository.insertCases(check.id(), cases(3));

        repository.insertCases(check.id(), cases(5));

        assertThat(repository.caseCount(check.id())).isEqualTo(3);
    }

    @Test
    void reproductionsAreRequestedInOrderAndRecordedPerCase() {
        var check = stored(queued("1111111"));
        repository.insertCases(check.id(), cases(4));

        var toReproduce = repository.casesToReproduce(check.id(), 3);
        assertThat(toReproduce).extracting(FidelityCase::ordinal).containsExactly(0, 1, 2);
        var reproduction = UUID.randomUUID();
        repository.updateCase(check.id(), toReproduce.getFirst().reproducing(reproduction));

        assertThat(repository.casesToReproduce(check.id(), 10))
                .extracting(FidelityCase::ordinal)
                .containsExactly(1, 2, 3);
        assertThat(repository.casesAwaitingReproduction(check.id()))
                .singleElement()
                .satisfies(waiting -> assertThat(waiting.reproductionId()).isEqualTo(reproduction));
    }

    @Test
    void aDivergenceAndAFailureAreStoredWithTheirCases() {
        var check = stored(queued("1111111"));
        repository.insertCases(check.id(), cases(3));
        var stored = repository.cases(check.id(), null, null, 10, 0).items();
        repository.updateCase(check.id(), stored.get(0).matched());
        repository.updateCase(
                check.id(),
                stored.get(1)
                        .diverged(new FirstDivergence(12, "DECISION", Map.of("seatIndex", 1), Map.of("seatIndex", 2))));
        repository.updateCase(
                check.id(), stored.get(2).unreproducible(new FidelityFailure("RUNNER_TIMEOUT", "timed out")));

        var counts = repository.counts(check.id());
        assertThat(counts.matched()).isEqualTo(1);
        assertThat(counts.diverged()).isEqualTo(1);
        assertThat(counts.unreproducible()).isEqualTo(1);
        assertThat(counts.pending()).isZero();
        var diverged = repository.cases(check.id(), null, FidelityCaseResult.DIVERGED, 10, 0);
        assertThat(diverged.items()).singleElement().satisfies(item -> {
            assertThat(item.firstDivergence().step()).isEqualTo(12);
            assertThat(item.firstDivergence().kind()).isEqualTo("DECISION");
            assertThat(item.firstDivergence().expected()).containsEntry("seatIndex", 1);
            assertThat(item.failure()).isNull();
        });
        var failed = repository.cases(check.id(), null, FidelityCaseResult.UNREPRODUCIBLE, 10, 0);
        assertThat(failed.items().getFirst().failure().code()).isEqualTo("RUNNER_TIMEOUT");
    }

    @Test
    void casesFilterByVariantAndResultAndPage() {
        var check = stored(queued("1111111"));
        repository.insertCases(check.id(), cases(6));

        var low = repository.cases(check.id(), VariantLabel.LOW, null, 10, 0);
        assertThat(low.total()).isEqualTo(3);
        assertThat(low.items()).extracting(FidelityCase::ordinal).containsExactly(1, 3, 5);
        var page = repository.cases(check.id(), null, null, 2, 2);
        assertThat(page.total()).isEqualTo(6);
        assertThat(page.items()).extracting(FidelityCase::ordinal).containsExactly(2, 3);
        assertThat(repository
                        .cases(check.id(), null, FidelityCaseResult.MATCH, 10, 0)
                        .total())
                .isZero();
    }
}
