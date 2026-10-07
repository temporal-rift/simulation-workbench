package io.github.temporalrift.workbench.experiment.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Deterministically enumerates every case coordinate of a frozen experiment. Ordering is stable:
 * seeds numerically, faction sets lexicographically, variants and policies in manifest order, and
 * {@code CYCLIC} rotations ascending. Every coordinate carries a deterministic {@code caseKey}.
 */
public final class MatrixPreviewService {

    private MatrixPreviewService() {}

    public static List<CaseCoordinate> preview(JsonNode manifest, String manifestDigest) {
        var view = ExperimentValidator.validate(manifest);
        var seeds = view.seeds().stream()
                .sorted(MatrixPreviewService::compareUint64)
                .toList();
        var sets = view.factionSets().stream()
                .sorted(MatrixPreviewService::compareSets)
                .toList();
        var cases = new ArrayList<CaseCoordinate>();
        for (String seed : seeds) {
            for (String variant : view.variantLabels()) {
                for (var policy : view.policies()) {
                    cases.addAll(baseCases(manifestDigest, seed, variant, policy, sets));
                }
            }
        }
        return List.copyOf(cases);
    }

    private static List<CaseCoordinate> baseCases(
            String manifestDigest,
            String seed,
            String variant,
            ExperimentValidator.PolicyRef policy,
            List<List<String>> sets) {
        var cases = new ArrayList<CaseCoordinate>();
        for (List<String> set : sets) {
            for (int rotation = 0; rotation < set.size(); rotation++) {
                cases.add(coordinate(manifestDigest, seed, variant, policy, set, rotation));
            }
        }
        return cases;
    }

    private static CaseCoordinate coordinate(
            String manifestDigest,
            String seed,
            String variant,
            ExperimentValidator.PolicyRef policy,
            List<String> sortedFactions,
            int rotation) {
        int players = sortedFactions.size();
        var seats = new ArrayList<SeatAssignment>();
        for (int seat = 0; seat < players; seat++) {
            seats.add(new SeatAssignment(
                    seat, sortedFactions.get((seat + rotation) % players), policy.id(), policy.version()));
        }
        var key = String.join(
                "|",
                manifestDigest,
                seed,
                variant,
                policy.id(),
                policy.version(),
                String.valueOf(players),
                String.join(",", sortedFactions),
                String.valueOf(rotation));
        var caseKey = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return new CaseCoordinate(
                caseKey, seed, variant, players, List.copyOf(sortedFactions), rotation, List.copyOf(seats));
    }

    private static int compareUint64(String left, String right) {
        int length = Integer.compare(left.length(), right.length());
        return length != 0 ? length : left.compareTo(right);
    }

    private static int compareSets(List<String> left, List<String> right) {
        int size = Integer.compare(left.size(), right.size());
        if (size != 0) {
            return size;
        }
        return Comparator.<List<String>, String>comparing(Object::toString).compare(left, right);
    }

    /** One deterministic case coordinate of the cohort matrix. */
    public record CaseCoordinate(
            UUID caseKey,
            String seed,
            String variantLabel,
            int playerCount,
            List<String> factionSet,
            int rotation,
            List<SeatAssignment> seats) {}

    /** One seat of a case coordinate. */
    public record SeatAssignment(int seatIndex, String faction, String policyId, String policyVersion) {}
}
