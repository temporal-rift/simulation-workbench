package io.github.temporalrift.workbench.fidelity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FidelityCheckTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static FidelityEnvironment environment(String revision) {
        var build = new FidelityEnvironment.ServiceBuild("sha256:" + "1".repeat(64), revision);
        return new FidelityEnvironment(build, build, build, "b".repeat(64), new TreeMap<>());
    }

    private static FidelityCheck queued() {
        return FidelityCheck.queued(
                UUID.randomUUID(),
                "42",
                environment("1111111"),
                environment("1111111"),
                "lane-1",
                List.of(new FidelityVariant(VariantLabel.BASELINE, "a".repeat(64), new TreeMap<String, BigDecimal>())),
                NOW);
    }

    @Test
    void aQueuedCheckHasNoVerdict() {
        var check = queued();

        assertThat(check.state()).isEqualTo(FidelityCheckState.QUEUED);
        assertThat(check.state().isOpen()).isTrue();
        assertThat(check.verdict()).isNull();
        assertThat(check.simulator().sourceRevisions().gameService()).isEqualTo("1111111");
    }

    @Test
    void aCheckPassesOnlyAfterItHasBegun() {
        var check = queued();

        assertThatThrownBy(() -> check.passed(NOW)).isInstanceOf(IllegalStateException.class);
        var running = check.begun(UUID.randomUUID(), UUID.randomUUID(), NOW);
        var passed = running.passed(NOW.plusSeconds(1));

        assertThat(running.state()).isEqualTo(FidelityCheckState.RUNNING);
        assertThat(running.startedAt()).isEqualTo(NOW);
        assertThat(passed.verdict()).isEqualTo(FidelityVerdict.PASSED);
        assertThat(passed.finishedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(passed.failure()).isNull();
    }

    @Test
    void aFailureOfTheCasesCarriesNoCheckFailure() {
        var failed = queued().begun(UUID.randomUUID(), UUID.randomUUID(), NOW).failedByCases(NOW);

        assertThat(failed.verdict()).isEqualTo(FidelityVerdict.FAILED);
        assertThat(failed.failure()).isNull();
    }

    @Test
    void aCheckThatCannotCompleteCarriesItsFailure() {
        var failed = queued().failed(new FidelityFailure("RUN_FAILED", "The run failed"), NOW);

        assertThat(failed.state()).isEqualTo(FidelityCheckState.FAILED);
        assertThat(failed.verdict()).isEqualTo(FidelityVerdict.FAILED);
        assertThat(failed.failure().code()).isEqualTo("RUN_FAILED");
    }

    @Test
    void aFinishedCheckNeverChangesAgain() {
        var passed = queued().begun(UUID.randomUUID(), UUID.randomUUID(), NOW).passed(NOW);

        var failure = new FidelityFailure("X", "no");
        assertThatThrownBy(() -> passed.failed(failure, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> passed.failedByCases(NOW)).isInstanceOf(IllegalStateException.class);
    }
}
