package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;

/** The frozen experiments a run executes. */
public interface ExperimentSource {

    /** The execution inputs of a frozen experiment, or empty when it does not exist. */
    Optional<Plan> plan(UUID experimentId);

    /** The plan and every case in stable order, read once, or empty when the experiment does not exist. */
    Optional<Snapshot> snapshot(UUID experimentId);

    record Snapshot(Plan plan, List<PlannedCase> cases) {
        public Snapshot {
            cases = List.copyOf(cases);
        }
    }

    record Plan(
            String manifestDigest,
            String manifestJson,
            int concurrency,
            int caseWallTimeoutSeconds,
            int maxRejectedCandidatesPerWindow,
            List<Variant> variants) {
        public Plan {
            variants = List.copyOf(variants);
        }
    }

    /**
     * The frozen effective rules of a variant, or empty when the experiment does not retain the baseline document they
     * are built from.
     */
    Optional<CaseRules> rules(UUID experimentId, String variantLabel);

    /** A rules variant, the bundle it names and the fit a lane needs to play it. */
    record Variant(String label, BundleDigests bundle, LaneFit fit) {}

    record PlannedCase(UUID caseKey, String seed, String variantLabel, int playerCount, List<SeatPlan> seats) {
        public PlannedCase {
            seats = List.copyOf(seats);
        }
    }
}
