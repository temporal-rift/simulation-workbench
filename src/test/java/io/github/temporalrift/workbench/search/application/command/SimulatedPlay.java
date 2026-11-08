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
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyNotAvailableException;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyRef;
import io.github.temporalrift.workbench.search.domain.campaign.SeatTable;
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

    static final String POISONED = "poisoned";

    private Runnable beforeRaise;

    /** Runs {@code action} once, just before the next time a run is allowed to play: a command landing mid-step. */
    synchronized void beforeNextRaise(Runnable action) {
        this.beforeRaise = action;
    }

    synchronized List<Experiment> frozen() {
        return List.copyOf(experiments.values());
    }

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

    record Experiment(int waveIndex, List<String> candidates, List<String> seeds, List<SeatTable> tables) {}

    @Override
    public ParameterGrid grid(CampaignDefinition definition) {
        if (POISONED.equals(definition.name())) {
            throw new IllegalStateException("this campaign cannot be generated");
        }
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
    public void check(CampaignDefinition definition) {
        // every definition is acceptable
    }

    @Override
    public boolean servesCriterion(int criterionVersion) {
        return criterionVersion == 1;
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
            List<String> seeds,
            List<SeatTable> tables) {
        experiments.putIfAbsent(
                idempotencyKey,
                new Experiment(waveIndex, candidates.stream().map(Candidate::id).toList(), seeds, tables));
        return idempotencyKey;
    }

    @Override
    public int casesPerGame(CampaignDefinition definition, List<SeatTable> tables) {
        return 10;
    }

    private boolean policiesServed = true;

    /** Whether the catalog has a bundle for the policies asked for. */
    synchronized void policiesServed(boolean served) {
        this.policiesServed = served;
    }

    @Override
    public synchronized List<tools.jackson.databind.JsonNode> bundles(List<PolicyRef> policies) {
        if (!policiesServed) {
            throw new PolicyNotAvailableException("no bundle serves the policies");
        }
        return List.of();
    }

    private boolean simulatorEnabled = true;

    /** Whether the simulator is enabled. */
    synchronized void simulator(boolean enabled) {
        this.simulatorEnabled = enabled;
    }

    @Override
    public synchronized boolean simulatorEnabled() {
        return simulatorEnabled;
    }

    private int refuseFreezeAt = -1;
    private int freezes;

    /** Makes the n-th confirmation experiment frozen from now on (counting from 1) be refused, once. */
    synchronized void refuseConfirmationFreezeNumber(int number) {
        this.freezes = 0;
        this.refuseFreezeAt = number;
    }

    @Override
    public synchronized UUID freezeConfirmation(
            UUID idempotencyKey,
            CampaignDefinition definition,
            Candidate candidate,
            List<String> seeds,
            List<SeatTable> tables) {
        if (++freezes == refuseFreezeAt) {
            throw new CampaignPlayRefusedException("the experiment is refused");
        }
        experiments.putIfAbsent(idempotencyKey, new Experiment(-1, List.of(candidate.id()), seeds, tables));
        return idempotencyKey;
    }

    // --- runs ---

    private static final class Run {
        private final UUID experiment;
        private RunPhase phase = RunPhase.ACTIVE;
        private int concurrency;
        private String failure;

        Run(UUID experiment, int concurrency) {
            this.experiment = experiment;
            this.concurrency = concurrency;
        }
    }

    private boolean runsFit = true;

    /** Whether some configured lane fits the variants of a run being started. */
    synchronized void runsFit(boolean fit) {
        this.runsFit = fit;
    }

    private void requireFit() {
        if (!runsFit) {
            throw new CampaignPlayRefusedException("no lane fits the variants");
        }
    }

    @Override
    public synchronized UUID start(UUID experimentId, UUID idempotencyKey) {
        requireFit();
        runs.putIfAbsent(idempotencyKey, new Run(experimentId, 1));
        experimentOfRun.put(idempotencyKey, experimentId);
        return idempotencyKey;
    }

    @Override
    public synchronized UUID startHeld(UUID experimentId, UUID idempotencyKey) {
        requireFit();
        runs.putIfAbsent(idempotencyKey, new Run(experimentId, 0));
        experimentOfRun.put(idempotencyKey, experimentId);
        return idempotencyKey;
    }

    @Override
    public synchronized Optional<RunProgress> progress(UUID runId) {
        return Optional.ofNullable(runs.get(runId)).map(run -> new RunProgress(run.phase, run.failure));
    }

    @Override
    public synchronized void setConcurrency(UUID runId, int concurrency) {
        if (concurrency > 0 && beforeRaise != null) {
            var action = beforeRaise;
            beforeRaise = null;
            action.run();
        }
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

    synchronized void finish(UUID runId) {
        runs.get(runId).phase = RunPhase.COMPLETED;
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

    private int confirmationSeedCount = 100;
    private final Map<String, Evaluation> confirmations = new HashMap<>();

    synchronized void confirmSeeds(int count) {
        this.confirmationSeedCount = count;
    }

    /** Scripts what confirming the subject's variant answers; every other subject is confirmed with score 1. */
    synchronized void confirmationOf(String subject, String verdict, Double score) {
        confirmations.put(subject, new Evaluation(verdict, score, List.of()));
    }

    @Override
    public List<PolicyRef> confirmationPolicies(int criterionVersion) {
        return List.of(new PolicyRef("faction-greedy", "1"), new PolicyRef("random", "1"));
    }

    @Override
    public List<SeatTable> screeningTables(int criterionVersion) {
        return List.of(
                new SeatTable(
                        "faction-greedy-v1", java.util.Collections.nCopies(5, new PolicyRef("faction-greedy", "1"))),
                new SeatTable("random-v1", java.util.Collections.nCopies(5, new PolicyRef("random", "1"))));
    }

    @Override
    public List<SeatTable> confirmationTables(int criterionVersion) {
        return CONFIRMATION_TABLES;
    }

    /** The screening tables plus a mixed table that only confirmation plays. */
    static final List<SeatTable> CONFIRMATION_TABLES = List.of(
            new SeatTable("faction-greedy-v1", java.util.Collections.nCopies(5, new PolicyRef("faction-greedy", "1"))),
            new SeatTable("random-v1", java.util.Collections.nCopies(5, new PolicyRef("random", "1"))),
            new SeatTable(
                    "random-seat-vs-faction-greedy-v1",
                    List.of(
                            new PolicyRef("random", "1"),
                            new PolicyRef("faction-greedy", "1"),
                            new PolicyRef("faction-greedy", "1"),
                            new PolicyRef("faction-greedy", "1"),
                            new PolicyRef("faction-greedy", "1"))));

    @Override
    public synchronized int confirmationSeedCount(int criterionVersion) {
        return confirmationSeedCount;
    }

    @Override
    public synchronized Evaluation confirm(int criterionVersion, UUID runId, String variantLabel) {
        return confirmations.getOrDefault(variantLabel, new Evaluation("CONFIRMED", 1.0, List.of()));
    }

    static UUID idOf(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
