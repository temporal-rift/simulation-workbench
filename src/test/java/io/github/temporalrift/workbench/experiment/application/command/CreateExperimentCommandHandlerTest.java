package io.github.temporalrift.workbench.experiment.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.domain.IdempotencyConflictException;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.experiment.domain.port.out.IdempotencyStore;

class CreateExperimentCommandHandlerTest {

    private InMemoryExperiments experiments;
    private InMemoryIdempotency idempotency;
    private CreateExperimentCommandHandler handler;

    @BeforeEach
    void setUp() {
        experiments = new InMemoryExperiments();
        idempotency = new InMemoryIdempotency();
        handler = new CreateExperimentCommandHandler(
                experiments, idempotency, Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void identicalIdempotencyKeyAndBodyReturnsOriginalExperiment() {
        var key = UUID.randomUUID();
        var manifest = ExperimentManifests.singleSeedSinglePolicySingleVariant();

        var first = handler.handle(new CreateExperimentUseCase.Command(key, manifest));
        var second = handler.handle(new CreateExperimentUseCase.Command(key, manifest.deepCopy()));

        assertThat(second.experimentId()).isEqualTo(first.experimentId());
        assertThat(experiments.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithChangedBodyConflicts() {
        var key = UUID.randomUUID();
        handler.handle(new CreateExperimentUseCase.Command(key, ExperimentManifests.valid()));
        var changed = ExperimentManifests.valid("43", "a".repeat(64), "b".repeat(64));

        assertThatThrownBy(() -> handler.handle(new CreateExperimentUseCase.Command(key, changed)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(experiments.count()).isEqualTo(1);
    }

    @Test
    void missingKeyIsRejected() {
        assertThatThrownBy(() -> handler.handle(new CreateExperimentUseCase.Command(null, ExperimentManifests.valid())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static class InMemoryExperiments implements ExperimentRepository {

        private final Map<UUID, StoredExperiment> rows = new HashMap<>();

        @Override
        public void save(
                UUID experimentId, String manifestDigest, String manifestJson, String name, Instant createdAt) {
            rows.put(experimentId, new StoredExperiment(experimentId, manifestDigest, manifestJson, name, createdAt));
        }

        @Override
        public Optional<StoredExperiment> findById(UUID experimentId) {
            return Optional.ofNullable(rows.get(experimentId));
        }

        int count() {
            return rows.size();
        }
    }

    static class InMemoryIdempotency implements IdempotencyStore {

        private final Map<UUID, Claim> rows = new HashMap<>();

        @Override
        public Optional<Claim> findByKey(UUID key) {
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public void claim(UUID key, String requestHash, UUID experimentId, Instant createdAt) {
            rows.put(key, new Claim(key, requestHash, experimentId, createdAt));
        }
    }
}
