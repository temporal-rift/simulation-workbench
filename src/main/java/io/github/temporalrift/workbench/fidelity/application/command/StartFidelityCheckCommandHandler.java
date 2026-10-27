package io.github.temporalrift.workbench.fidelity.application.command;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.application.port.in.StartFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.domain.CheckClaim;
import io.github.temporalrift.workbench.fidelity.domain.CheckCreation;
import io.github.temporalrift.workbench.fidelity.domain.EdgeVariants;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckActiveException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityExperimentRefusedException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityIdempotencyConflictException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityVariant;
import io.github.temporalrift.workbench.fidelity.domain.InvalidSeedException;
import io.github.temporalrift.workbench.fidelity.domain.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityExperiments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * Accepts a fidelity check for a seed: the simulator and a build-equivalent real lane are chosen and the three variants
 * are resolved now, so the record says from the start what will be compared. The same idempotency key with the same
 * seed returns the original check; with another seed it conflicts. Only one check may be queued or running.
 */
public class StartFidelityCheckCommandHandler implements StartFidelityCheckUseCase {

    static final String OPERATION = "startFidelityCheck";

    private static final Pattern UINT64 = Pattern.compile("0|[1-9]\\d*");
    private static final BigInteger MAX_UINT64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private final FidelityRepository checks;
    private final FidelityEnvironments environments;
    private final FidelityExperiments experiments;
    private final EdgeVariants edgeVariants;
    private final Clock clock;

    public StartFidelityCheckCommandHandler(
            FidelityRepository checks,
            FidelityEnvironments environments,
            FidelityExperiments experiments,
            EdgeVariants edgeVariants,
            Clock clock) {
        this.checks = checks;
        this.environments = environments;
        this.experiments = experiments;
        this.edgeVariants = edgeVariants;
        this.clock = clock;
    }

    @Override
    public FidelityCheckView handle(Command command) {
        if (command.idempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        requireUint64(command.seed());
        var requestHash = Sha256.hexUtf8(OPERATION + "|" + command.seed());
        var replay = checks.findClaim(command.idempotencyKey());
        if (replay.isPresent()) {
            return replayed(replay.get(), requestHash);
        }
        if (checks.open().isPresent()) {
            throw new FidelityCheckActiveException();
        }
        var simulator = environments
                .simulator()
                .orElseThrow(() -> new SimulatorNotAvailableException("The simulator is not enabled"));
        var lane = environments
                .equivalentLane()
                .orElseThrow(() -> new SimulatorNotAvailableException(
                        "No configured real lane has the simulator's content, source revisions, contract versions"
                                + " and bot identities"));
        var queued = FidelityCheck.queued(
                UUID.randomUUID(),
                command.seed(),
                simulator.environment(),
                lane.environment(),
                lane.laneId(),
                variants(simulator),
                clock.instant());
        return switch (checks.create(command.idempotencyKey(), requestHash, queued)) {
            case CheckCreation.Created _ -> view(queued);
            case CheckCreation.Existing(var claim) -> replayed(claim, requestHash);
            case CheckCreation.Active _ -> throw new FidelityCheckActiveException();
        };
    }

    private List<FidelityVariant> variants(FidelityEnvironments.Simulator simulator) {
        var variants = new ArrayList<FidelityVariant>();
        variants.add(variant(VariantLabel.BASELINE, simulator, Map.of()));
        variants.add(variant(VariantLabel.LOW, simulator, edgeVariants.low()));
        variants.add(variant(VariantLabel.HIGH, simulator, edgeVariants.high()));
        return variants;
    }

    private FidelityVariant variant(
            VariantLabel label, FidelityEnvironments.Simulator simulator, Map<String, BigDecimal> overrides) {
        try {
            var rules = experiments.previewVariant(
                    simulator.rulesArtifactDigest(), edgeVariants.parameterSpaceVersion(), overrides);
            return new FidelityVariant(label, rules.effectiveRulesDigest(), rules.overrides());
        } catch (FidelityExperimentRefusedException e) {
            throw new SimulatorNotAvailableException("The simulator's baseline rules cannot carry the "
                    + label.label() + " variant of parameter space " + edgeVariants.parameterSpaceVersion() + ": "
                    + e.getMessage());
        }
    }

    private FidelityCheckView replayed(CheckClaim claim, String requestHash) {
        if (!claim.requestHash().equals(requestHash)) {
            throw new FidelityIdempotencyConflictException();
        }
        return view(claim.check());
    }

    private FidelityCheckView view(FidelityCheck check) {
        return new FidelityCheckView(check, checks.counts(check.id()));
    }

    private static void requireUint64(String seed) {
        if (seed == null || !UINT64.matcher(seed).matches() || new BigInteger(seed).compareTo(MAX_UINT64) > 0) {
            throw new InvalidSeedException(seed);
        }
    }
}
