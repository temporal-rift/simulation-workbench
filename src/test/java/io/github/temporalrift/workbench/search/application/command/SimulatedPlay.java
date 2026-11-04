package io.github.temporalrift.workbench.search.application.command;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.ParameterGrid;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateValidation;
import io.github.temporalrift.workbench.search.domain.port.out.FidelityGate;
import io.github.temporalrift.workbench.shared.execution.RunPhase;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * The experiment, run, evaluation and fidelity sides of a campaign, simulated: runs play when the test lets them, and
 * a candidate score is a fixed function of its id, so everything a campaign decides is a function of its definition.
 */
final class SimulatedPlay implements CampaignExperiments, CampaignRuns, CandidateEvaluation, FidelityGate {

    private final AtomicBoolean fidelityEstablished = new AtomicBoolean(true);
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, UUID> experimentOfRun = new HashMap<>();
    private final Map<UUID, Experiment> experiments = new HashMap<>();

    void fidelity(boolean established) {
        fidelityEstablished.set(established);
    }

    @Override
    public boolean established() {
        return fidelityEstablished.get();
    }

    // --- experiments ---

    record Experiment(int waveIndex, List<String> candidates, List<String> seeds) {}

    @Override
    public ParameterGrid grid(CampaignDefinition definition) {
        return new ParameterGrid(List.of(
                axis("game.rules.a", 0, 4, 2),
                axis("game.rules.b", 0, 4, 2),
                axis("game.rules.c", 0, 9, 5),
                axis("game.rules.d", 0, 9, 5)));
    }

    private static ParameterGrid.Axis axis(String key, int min, int max, int baseline) {
        return new ParameterGrid.Axis(
                key, BigDecimal.valueOf(min), BigDecimal.valueOf(max), BigDecimal.ONE, BigDecimal.valueOf(baseline));
    }

    @Override
    public CandidateValidation validation(CampaignDefinition definition) {
        return overrides -> Optional.of(new CandidateValidation.Validated(
                Sha256.hexUtf8(new java.util.TreeMap<>(overrides).toString()), new java.util.TreeMap<>(overrides)));
    }

    @Override
    public synchronized UUID freeze(
            UUID idempotencyKey,
            CampaignDefinition definition,
            int waveIndex,
            List<Candidate> candidates,
            List<String> seeds) {
        experiments.putIfAbsent(
                idempotencyKey,
                new Experiment(waveIndex, candidates.stream().map(Candidate::id).toList(), seeds));
        return idempotencyKey;
    }

    @Override
    public int casesPerGame(CampaignDefinition definition) {
        return 10;
    }

    // --- runs ---

    private static final class Run {
        private final UUID experiment;
        private RunPhase phase = RunPhase.ACTIVE;
        private int concurrency = 1;
        private String failure;

        Run(UUID experiment) {
            this.experiment = experiment;
        }
    }

    @Override
    public synchronized UUID start(UUID experimentId, UUID idempotencyKey) {
        runs.putIfAbsent(idempotencyKey, new Run(experimentId));
        experimentOfRun.put(idempotencyKey, experimentId);
        return idempotencyKey;
    }

    @Override
    public synchronized Optional<RunProgress> progress(UUID runId) {
        return Optional.ofNullable(runs.get(runId)).map(run -> new RunProgress(run.phase, run.failure));
    }

    @Override
    public synchronized void setConcurrency(UUID runId, int concurrency) {
        var run = runs.get(runId);
        if (run != null && !run.phase.isFinal()) {
            run.concurrency = concurrency;
        }
    }

    @Override
    public synchronized void resume(UUID runId) {
        var run = runs.get(runId);
        if (run != null && run.phase == RunPhase.INTERRUPTED) {
            run.phase = RunPhase.ACTIVE;
        }
    }

    @Override
    public synchronized void cancel(UUID runId) {
        var run = runs.get(runId);
        if (run != null && !run.phase.isFinal()) {
            run.phase = RunPhase.CANCELLED;
        }
    }

    @Override
    public synchronized Set<String> finishedSeeds(UUID runId, String variantLabel) {
        var run = runs.get(runId);
        if (run == null || run.phase != RunPhase.COMPLETED) {
            return Set.of();
        }
        var experiment = experiments.get(run.experiment);
        return experiment.candidates().contains(variantLabel) ? new HashSet<>(experiment.seeds()) : Set.of();
    }

    synchronized int concurrencyOf(UUID runId) {
        return runs.get(runId).concurrency;
    }

    synchronized RunPhase phaseOf(UUID runId) {
        return runs.get(runId).phase;
    }

    /** The games the executors play: every run allowed to play at least one case at a time completes. */
    synchronized boolean playAllowed() {
        var played = false;
        for (var run : runs.values()) {
            if (run.phase == RunPhase.ACTIVE && run.concurrency > 0) {
                run.phase = RunPhase.COMPLETED;
                played = true;
            }
        }
        return played;
    }

    /** A restart: every run that was playing is interrupted until it is resumed. */
    synchronized void restart() {
        runs.values().stream()
                .filter(run -> run.phase == RunPhase.ACTIVE)
                .forEach(run -> run.phase = RunPhase.INTERRUPTED);
    }

    synchronized void fail(UUID runId, String message) {
        var run = runs.get(runId);
        run.phase = RunPhase.FAILED;
        run.failure = message;
    }

    synchronized List<UUID> runIds() {
        return new ArrayList<>(runs.keySet());
    }

    // --- evaluation ---

    @Override
    public Screening screen(int criterionVersion, List<CandidateGames> candidates) {
        var evaluations = new HashMap<String, Evaluation>();
        for (var candidate : candidates) {
            var number = Integer.parseInt(candidate.candidateId().substring(2));
            var rejected = number % 5 == 3;
            evaluations.put(
                    candidate.candidateId(),
                    new Evaluation(
                            rejected ? "REJECTED" : "CONFIRMED",
                            (double) ((number * 7 + 3) % 11) + number / 100.0,
                            rejected ? List.of("line-" + number) : List.of()));
        }
        return new Screening(evaluations, Map.of());
    }

    static UUID idOf(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
