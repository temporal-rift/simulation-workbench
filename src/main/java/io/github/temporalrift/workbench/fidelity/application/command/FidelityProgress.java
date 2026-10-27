package io.github.temporalrift.workbench.fidelity.application.command;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.fidelity.application.port.in.AdvanceFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState;
import io.github.temporalrift.workbench.fidelity.domain.FidelityExperimentRefusedException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityFailure;
import io.github.temporalrift.workbench.fidelity.domain.FidelityMatrix;
import io.github.temporalrift.workbench.fidelity.domain.FidelityPlaybackRefusedException;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityExperiments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityPlayback;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;

/**
 * Moves the open check forward one step at a time. Every step is safe to repeat: the experiment, the run and each
 * reproduction are requested with keys derived from the check, so a restart between a request and the record of its
 * answer asks again and gets the same one.
 *
 * <ol>
 *   <li>A queued check freezes its experiment and starts its run on the simulator.
 *   <li>When the run ends, its cases become the check's cases; a case the simulator could not play cannot be
 *       reproduced.
 *   <li>Reproductions are requested in batches on the lane chosen when the check was accepted, then read back as they
 *       finish.
 *   <li>When no case is pending, the check ends {@code PASSED} if and only if every case matched.
 * </ol>
 */
public class FidelityProgress implements AdvanceFidelityUseCase {

    static final String NOT_AVAILABLE = "SIMULATOR_NOT_AVAILABLE";
    static final String EXPERIMENT_REFUSED = "EXPERIMENT_REFUSED";
    static final String RUN_NOT_STARTED = "RUN_NOT_STARTED";
    static final String RUN_FAILED = "RUN_FAILED";
    static final String RUN_CANCELLED = "RUN_CANCELLED";
    static final String RUN_MISSING = "RUN_MISSING";
    static final String REPRODUCTION_REFUSED = "REPRODUCTION_REFUSED";
    static final String REPRODUCTION_FAILED = "REPRODUCTION_FAILED";

    private final FidelityRepository checks;
    private final FidelityEnvironments environments;
    private final FidelityExperiments experiments;
    private final FidelityPlayback playback;
    private final FidelityManifestFactory manifests;
    private final FidelitySettings settings;
    private final Clock clock;

    public FidelityProgress(
            FidelityRepository checks,
            FidelityEnvironments environments,
            FidelityExperiments experiments,
            FidelityPlayback playback,
            FidelityManifestFactory manifests,
            FidelitySettings settings,
            Clock clock) {
        this.checks = checks;
        this.environments = environments;
        this.experiments = experiments;
        this.playback = playback;
        this.manifests = manifests;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public boolean advance() {
        return checks.open().map(this::step).orElse(false);
    }

    private boolean step(FidelityCheck check) {
        return switch (check.state()) {
            case QUEUED -> {
                begin(check);
                yield true;
            }
            case RUNNING -> checks.caseCount(check.id()) == 0 ? awaitRun(check) : awaitReproductions(check);
            case PASSED, FAILED -> false;
        };
    }

    private void begin(FidelityCheck check) {
        var simulator = environments.simulator();
        if (simulator.isEmpty()
                || !simulator
                        .get()
                        .environment()
                        .sourceRevisions()
                        .equals(check.simulator().sourceRevisions())) {
            fail(check, NOT_AVAILABLE, "The simulator no longer embeds the revisions the check was accepted for");
            return;
        }
        if (environments.equivalentLane().isEmpty()) {
            fail(check, NOT_AVAILABLE, "No configured real lane is build-equivalent to the simulator any more");
            return;
        }
        try {
            var manifest = manifests.build(check.seed(), simulator.get(), check.variants(), policies());
            var experimentId = experiments.freeze(key(check, "experiment"), manifest);
            var runId = playback.startRun(experimentId, key(check, "run"));
            checks.update(check.begun(experimentId, runId, clock.instant()), FidelityCheckState.QUEUED);
        } catch (FidelityExperimentRefusedException | IllegalStateException e) {
            fail(check, EXPERIMENT_REFUSED, e.getMessage());
        } catch (FidelityPlaybackRefusedException e) {
            fail(check, RUN_NOT_STARTED, e.getMessage());
        }
    }

    private List<FidelityExperiments.Policy> policies() {
        var defined = experiments.baselinePolicies();
        return FidelityMatrix.POLICY_IDS.stream()
                .map(id -> defined.stream()
                        .filter(policy ->
                                policy.id().equals(id) && policy.version().equals(FidelityMatrix.POLICY_VERSION))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "baseline policy " + id + "@" + FidelityMatrix.POLICY_VERSION + " is not defined")))
                .toList();
    }

    private boolean awaitRun(FidelityCheck check) {
        var status = playback.run(check.runId());
        if (status.isEmpty()) {
            fail(check, RUN_MISSING, "The simulator run " + check.runId() + " no longer exists");
            return true;
        }
        switch (status.get().phase()) {
            case ACTIVE -> {
                return false;
            }
            case FAILED ->
                fail(
                        check,
                        RUN_FAILED,
                        status.get().failureMessage() == null
                                ? "The simulator run failed"
                                : status.get().failureMessage());
            case CANCELLED -> fail(check, RUN_CANCELLED, "The simulator run was cancelled");
            case COMPLETED -> materialise(check);
        }
        return true;
    }

    private void materialise(FidelityCheck check) {
        var played = playback.cases(check.runId());
        if (played.isEmpty()) {
            checks.update(check.failedByCases(clock.instant()), FidelityCheckState.RUNNING);
            return;
        }
        var cases = played.stream()
                .map(FidelityProgress::pending)
                .sorted(FidelityMatrix.ORDER)
                .toList();
        var numbered = new ArrayList<FidelityCase>();
        for (var ordinal = 0; ordinal < cases.size(); ordinal++) {
            var next = cases.get(ordinal).withOrdinal(ordinal);
            var source = played.stream()
                    .filter(candidate -> candidate.caseKey().equals(next.caseKey()))
                    .findFirst()
                    .orElseThrow();
            numbered.add(source.succeeded() ? next : next.unreproducible(source.failure()));
        }
        checks.insertCases(check.id(), numbered);
    }

    private static FidelityCase pending(FidelityPlayback.PlayedCase played) {
        return FidelityCase.pending(
                0,
                played.caseKey(),
                VariantLabel.ofLabel(played.variantLabel()),
                played.playerCount(),
                played.factionSet(),
                played.seatRotation(),
                played.policyId(),
                played.runCaseId());
    }

    private boolean awaitReproductions(FidelityCheck check) {
        var progressed = request(check);
        progressed |= collect(check);
        var counts = checks.counts(check.id());
        if (counts.settled()) {
            var settled = counts.passes() ? check.passed(clock.instant()) : check.failedByCases(clock.instant());
            checks.update(settled, FidelityCheckState.RUNNING);
            return true;
        }
        return progressed;
    }

    private boolean request(FidelityCheck check) {
        var toReproduce = checks.casesToReproduce(check.id(), settings.reproductionBatch());
        for (var fidelityCase : toReproduce) {
            try {
                var reproduction = playback.reproduce(
                        check.runId(),
                        fidelityCase.runCaseId(),
                        check.laneId(),
                        key(check, "reproduction|" + fidelityCase.caseKey()));
                checks.updateCase(check.id(), fidelityCase.reproducing(reproduction));
            } catch (FidelityPlaybackRefusedException e) {
                checks.updateCase(
                        check.id(),
                        fidelityCase.unreproducible(new FidelityFailure(REPRODUCTION_REFUSED, e.getMessage())));
            }
        }
        return !toReproduce.isEmpty();
    }

    private boolean collect(FidelityCheck check) {
        var progressed = false;
        for (var fidelityCase : checks.casesAwaitingReproduction(check.id())) {
            var reproduction = playback.reproduction(fidelityCase.reproductionId());
            if (reproduction.isEmpty()) {
                checks.updateCase(
                        check.id(),
                        fidelityCase.unreproducible(
                                new FidelityFailure(REPRODUCTION_FAILED, "The reproduction no longer exists")));
                progressed = true;
            } else if (reproduction.get().finished()) {
                checks.updateCase(check.id(), settle(fidelityCase, reproduction.get()));
                progressed = true;
            }
        }
        return progressed;
    }

    private static FidelityCase settle(FidelityCase fidelityCase, FidelityPlayback.Reproduced reproduction) {
        return switch (reproduction.status()) {
            case MATCH -> fidelityCase.matched();
            case DIVERGED -> fidelityCase.diverged(reproduction.firstDivergence());
            case FAILED ->
                fidelityCase.unreproducible(
                        reproduction.failure() != null
                                ? reproduction.failure()
                                : new FidelityFailure(REPRODUCTION_FAILED, "The reproduction could not run to an end"));
            case QUEUED, RUNNING -> fidelityCase;
        };
    }

    private void fail(FidelityCheck check, String code, String message) {
        checks.update(check.failed(new FidelityFailure(code, message), clock.instant()), check.state());
    }

    /** A key that is the same on every repetition of one request of one check. */
    private static UUID key(FidelityCheck check, String purpose) {
        return UUID.nameUUIDFromBytes(("fidelity|" + check.id() + "|" + purpose).getBytes(StandardCharsets.UTF_8));
    }
}
