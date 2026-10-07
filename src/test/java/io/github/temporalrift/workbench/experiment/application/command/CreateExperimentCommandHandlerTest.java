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
        var original = ExperimentManifests.valid();
        handler.handle(new CreateExperimentUseCase.Command(key, original));
        var changed = ExperimentManifests.valid("43", "a".repeat(64), "b".repeat(64));
        var replay = new CreateExperimentUseCase.Command(key, changed);

        assertThatThrownBy(() -> handler.handle(replay)).isInstanceOf(IdempotencyConflictException.class);
        assertThat(experiments.count()).isEqualTo(1);
    }

    @Test
    void lostRaceReturnsWinningExperimentAndRemovesOrphanRow() {
        var key = UUID.randomUUID();
        var winnerId = UUID.randomUUID();
        var manifest = ExperimentManifests.singleSeedSinglePolicySingleVariant();
        experiments.save(winnerId, "d".repeat(64), manifest.toString(), "threshold experiment", Instant.now());
        var racing = new RacingIdempotency(winnerId);
        var racingHandler = new CreateExperimentCommandHandler(
                experiments, racing, Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));

        var result = racingHandler.handle(new CreateExperimentUseCase.Command(key, manifest));

        assertThat(result.experimentId()).isEqualTo(winnerId);
        assertThat(experiments.count()).isEqualTo(1);
        assertThat(experiments.findById(winnerId)).isPresent();
    }

    @Test
    void missingKeyIsRejected() {
        var command = new CreateExperimentUseCase.Command(null, ExperimentManifests.valid());

        assertThatThrownBy(() -> handler.handle(command)).isInstanceOf(IllegalArgumentException.class);
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

        @Override
        public void delete(UUID experimentId) {
            rows.remove(experimentId);
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
        public boolean saveIfAbsent(UUID key, String requestHash, UUID experimentId, Instant createdAt) {
            return rows.putIfAbsent(key, new Claim(key, requestHash, experimentId, createdAt)) == null;
        }
    }

    /** Simulates losing an insert race: the first read is empty, the insert loses, and the re-read
     * finds the winner's claim carrying the hash this call just computed. */
    static class RacingIdempotency implements IdempotencyStore {

        private final UUID winnerId;
        private String requestHash;

        RacingIdempotency(UUID winnerId) {
            this.winnerId = winnerId;
        }

        @Override
        public Optional<Claim> findByKey(UUID key) {
            if (requestHash == null) {
                return Optional.empty();
            }
            return Optional.of(new Claim(key, requestHash, winnerId, Instant.now()));
        }

        @Override
        public boolean saveIfAbsent(UUID key, String requestHash, UUID experimentId, Instant createdAt) {
            this.requestHash = requestHash;
            return false;
        }
    }
}
