package io.github.temporalrift.workbench.experiment.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * Deterministically enumerates every case coordinate of a frozen experiment. Ordering is stable:
 * seeds numerically, faction sets lexicographically, variants and policies in manifest order, and
 * {@code CYCLIC} rotations ascending. Every coordinate carries a deterministic {@code caseKey}.
 */
public final class MatrixPreviewService {

    private MatrixPreviewService() {}

    public static List<CaseCoordinate> preview(JsonNode manifest, String manifestDigest) {
        var view = ExperimentValidator.validate(manifest);
        var cases = new ArrayList<CaseCoordinate>();
        page(view, manifestDigest, 0, Integer.MAX_VALUE, cases::add);
        return List.copyOf(cases);
    }

    /** The size of the matrix of a manifest, computed arithmetically without visiting any coordinate. */
    public static MatrixSize size(JsonNode manifest) {
        return size(ExperimentValidator.validate(manifest));
    }

    /** The size of the matrix of an already validated manifest. */
    public static MatrixSize size(ExperimentValidator.ManifestView view) {
        var coordinatesByPlayers = new TreeMap<Integer, Integer>();
        view.factionSets().forEach(set -> coordinatesByPlayers.merge(set.size(), set.size(), Integer::sum));
        long perVariantAndPolicy = (long) view.seeds().size() * view.policies().size();
        var breakdown = new ArrayList<Slice>();
        long total = 0;
        for (String variant : view.variantLabels()) {
            for (var entry : coordinatesByPlayers.entrySet()) {
                int count = Math.toIntExact(perVariantAndPolicy * entry.getValue());
                breakdown.add(new Slice(variant, entry.getKey(), count));
                total += count;
            }
        }
        return new MatrixSize(Math.toIntExact(total), List.copyOf(breakdown));
    }

    /**
     * The coordinates {@code offset ..< offset + limit} of the stable order of an already validated manifest, each
     * derived by index arithmetic, so the cost follows the window and not the matrix.
     */
    public static List<CaseCoordinate> page(
            ExperimentValidator.ManifestView view, String manifestDigest, int offset, int limit) {
        var window = new ArrayList<CaseCoordinate>();
        page(view, manifestDigest, offset, limit, window::add);
        return List.copyOf(window);
    }

    /** Visits only the coordinates of the window, in the stable order. */
    public static void page(
            ExperimentValidator.ManifestView view,
            String manifestDigest,
            int offset,
            int limit,
            Consumer<CaseCoordinate> visitor) {
        var seeds = view.seeds().stream()
                .sorted(MatrixPreviewService::compareUint64)
                .toList();
        var sets = view.factionSets().stream()
                .sorted(MatrixPreviewService::compareSets)
                .toList();
        long perBlock = sets.stream().mapToLong(List::size).sum();
        int variants = view.variantLabels().size();
        int policies = view.policies().size();
        long total = seeds.size() * (long) variants * policies * perBlock;
        long start = Math.max(0, offset);
        long end = Math.min(total, start + Math.max(0, limit));
        for (long index = start; index < end; index++) {
            long block = index / perBlock;
            visitor.accept(blockCoordinate(
                    manifestDigest,
                    seeds.get((int) (block / policies / variants)),
                    view.variantLabels().get((int) (block / policies % variants)),
                    view.policies().get((int) (block % policies)),
                    sets,
                    (int) (index % perBlock)));
        }
    }

    private static CaseCoordinate blockCoordinate(
            String manifestDigest,
            String seed,
            String variant,
            ExperimentValidator.PolicyRef policy,
            List<List<String>> sets,
            int positionInBlock) {
        int remaining = positionInBlock;
        for (List<String> set : sets) {
            if (remaining < set.size()) {
                return coordinate(manifestDigest, seed, variant, policy, set, remaining);
            }
            remaining -= set.size();
        }
        throw new IllegalStateException("position " + positionInBlock + " is outside its block");
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
                    seat,
                    Faction.valueOf(sortedFactions.get((seat + rotation) % players)),
                    policy.id(),
                    policy.version()));
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

    /** The number of coordinates of a matrix and how they split over variants and player counts. */
    public record MatrixSize(int total, List<Slice> breakdown) {}

    /** The coordinates of one variant at one player count; variants in manifest order, player counts ascending. */
    public record Slice(String variantLabel, int playerCount, int caseCount) {}

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
    public record SeatAssignment(int seatIndex, Faction faction, String policyId, String policyVersion) {}
}
