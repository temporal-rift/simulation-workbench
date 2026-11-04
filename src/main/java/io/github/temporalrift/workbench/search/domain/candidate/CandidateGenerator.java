package io.github.temporalrift.workbench.search.domain.candidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateValidation;
import io.github.temporalrift.workbench.shared.random.SplitMix64;

/**
 * Generates the candidates of a search. Round 0 is the baseline, its one-step perturbations in key order and a Latin
 * hypercube sample of the whole space; later rounds mutate survivors by steps that shrink every round and keep a
 * share of random candidates so the search does not settle in one region.
 *
 * A candidate that breaks a range, a step or a constraint, or makes rules an earlier candidate already makes, is
 * redrawn and never repaired. Every draw comes from a stream of the search master seed and the round, so the same
 * inputs always give the same candidates in the same order.
 */
public final class CandidateGenerator {

    /**
     * Draws a single place may use before the space is declared too small. The shipped parameter space has 46
     * parameters under sum and ordering constraints, so about one independent draw in 3,000 is valid.
     */
    static final int MAX_DRAWS_PER_PLACE = 100_000;

    /** One in this many new candidates of a later round is random rather than a mutation. */
    static final int RANDOM_SHARE = 4;

    /** The most steps a mutation moves a parameter in round 1; each later round halves it, down to one. */
    static final int FIRST_ROUND_MUTATION_STEPS = 4;

    /** The most parameters one mutation changes. */
    static final int MAX_MUTATED_PARAMETERS = 3;

    private static final int JITTER_RESOLUTION = 1_000_000;

    private final ParameterGrid grid;
    private final CandidateValidation validation;
    private final SearchSeeds seeds;
    private final SearchAlgorithm algorithm;

    public CandidateGenerator(
            ParameterGrid grid, CandidateValidation validation, SearchSeeds seeds, SearchAlgorithm algorithm) {
        this.grid = grid;
        this.validation = validation;
        this.seeds = seeds;
        this.algorithm = algorithm;
    }

    /**
     * Round 0: the baseline, then for each parameter in key order the grid value one step below and one step above
     * the baseline where they exist, then a Latin hypercube sample for the remaining places. When the baseline and
     * the perturbations reach {@code initialCandidates}, they are taken in order up to it and no sample is drawn. A
     * perturbation that breaks a constraint is not a candidate and is left out.
     *
     * @throws CandidateGenerationExhaustedException when the space cannot supply enough distinct valid candidates
     */
    public List<Candidate> roundZero() {
        var round = new Round(0, 0, List.of());
        var baselinePoint = grid.baselinePoint();
        round.accept(baselinePoint)
                .orElseThrow(() -> new IllegalStateException("the baseline rules do not validate against themselves"));
        for (var axis = 0; axis < grid.axes().size() && !round.filled(algorithm.initialCandidates()); axis++) {
            var baseIndex = grid.axes().get(axis).baselineIndex();
            for (var delta : new int[] {-1, 1}) {
                var index = baseIndex + delta;
                if (index >= 0
                        && index <= grid.axes().get(axis).highestIndex()
                        && !round.filled(algorithm.initialCandidates())) {
                    var point = new ArrayList<>(baselinePoint);
                    point.set(axis, index);
                    round.accept(point);
                }
            }
        }
        var missing = algorithm.initialCandidates() - round.size();
        if (missing > 0) {
            var sample = latinHypercube(missing, seeds.stream(0, "latin-hypercube"));
            var redraws = seeds.stream(0, "redraw");
            for (var point : sample) {
                round.place(point, () -> randomPoint(redraws));
            }
        }
        return round.candidates();
    }

    /**
     * A later round: local mutations of {@code survivors}, taken in the order given and wrapping round when there
     * are more places than survivors, and a fixed share of random candidates.
     *
     * @param survivors the candidates still in play, best first
     * @param earlier every candidate generated so far, which no new candidate may duplicate
     * @param firstIndex the generation index of the first new candidate
     * @throws CandidateGenerationExhaustedException when the space cannot supply enough distinct valid candidates
     */
    public List<Candidate> laterRound(
            int round, List<Candidate> survivors, Collection<Candidate> earlier, int firstIndex) {
        if (round < 1 || survivors.isEmpty()) {
            throw new IllegalArgumentException("a later round needs a round after 0 and at least one survivor");
        }
        var generated = new Round(round, firstIndex, earlier);
        var places = newCandidatesIn(round);
        var random = places / RANDOM_SHARE;
        var mutations = seeds.stream(round, "mutation");
        for (var place = 0; place < places - random; place++) {
            var parent = survivors.get(place % survivors.size());
            generated.place(mutate(parent.point(), round, mutations), () -> mutate(parent.point(), round, mutations));
        }
        var randoms = seeds.stream(round, "random");
        for (var place = 0; place < random; place++) {
            generated.place(randomPoint(randoms), () -> randomPoint(randoms));
        }
        return generated.candidates();
    }

    /** How many candidates a round after 0 adds: half the initial count, halved again every round, at least one. */
    public int newCandidatesIn(int round) {
        var places = (long) algorithm.initialCandidates();
        var divisor = 2L;
        for (var step = 1; step < round && divisor <= places; step++) {
            divisor *= 2;
        }
        return (int) Math.max(1, (places + divisor - 1) / divisor);
    }

    /** The most steps a mutation moves one parameter in {@code round}: shrinks every round, never below one. */
    public static int maxMutationSteps(int round) {
        return Math.max(1, FIRST_ROUND_MUTATION_STEPS >> Math.clamp(round - 1, 0, 30));
    }

    private List<Integer> mutate(List<Integer> parent, int round, SplitMix64 stream) {
        var point = new ArrayList<>(parent);
        var axes = new ArrayList<Integer>();
        for (var axis = 0; axis < grid.axes().size(); axis++) {
            axes.add(axis);
        }
        var changed = 1 + stream.nextInt(Math.min(MAX_MUTATED_PARAMETERS, axes.size()));
        var maxSteps = maxMutationSteps(round);
        for (var pick = 0; pick < changed; pick++) {
            var axis = axes.remove(stream.nextInt(axes.size()));
            var steps = 1 + stream.nextInt(maxSteps);
            var moved = point.get(axis) + (stream.nextInt(2) == 0 ? -steps : steps);
            point.set(axis, Math.clamp(moved, 0, grid.axes().get(axis).highestIndex()));
        }
        return point;
    }

    private List<Integer> randomPoint(SplitMix64 stream) {
        return grid.axes().stream()
                .map(axis -> stream.nextInt(axis.highestIndex() + 1))
                .toList();
    }

    /** {@code count} points whose every parameter visits each of {@code count} equal strata of its grid once. */
    private List<List<Integer>> latinHypercube(int count, SplitMix64 stream) {
        var points = new ArrayList<List<Integer>>();
        for (var sample = 0; sample < count; sample++) {
            points.add(new ArrayList<>());
        }
        for (var axis : grid.axes()) {
            var strata = new ArrayList<Integer>();
            for (var stratum = 0; stratum < count; stratum++) {
                strata.add(stratum);
            }
            for (var swap = count - 1; swap > 0; swap--) {
                java.util.Collections.swap(strata, swap, stream.nextInt(swap + 1));
            }
            for (var sample = 0; sample < count; sample++) {
                var jitter = stream.nextInt(JITTER_RESOLUTION) / (double) JITTER_RESOLUTION;
                var position = (strata.get(sample) + jitter) / count;
                var index = (int) Math.floor(position * (axis.highestIndex() + 1));
                points.get(sample).add(Math.min(index, axis.highestIndex()));
            }
        }
        return List.copyOf(points);
    }

    /** The candidates one round has accepted so far, and the rules every candidate of the campaign makes. */
    private final class Round {

        private final int number;
        private final int firstIndex;
        private final Set<String> digests = new HashSet<>();
        private final List<Candidate> accepted = new ArrayList<>();

        Round(int number, int firstIndex, Collection<Candidate> earlier) {
            this.number = number;
            this.firstIndex = firstIndex;
            earlier.forEach(candidate -> digests.add(candidate.effectiveRulesDigest()));
        }

        int size() {
            return accepted.size();
        }

        boolean filled(int wanted) {
            return accepted.size() >= wanted;
        }

        List<Candidate> candidates() {
            return List.copyOf(accepted);
        }

        /** Accepts the point if it validates and makes new rules, with no redraws. */
        Optional<Candidate> accept(List<Integer> point) {
            return accept(point, 0);
        }

        /** Accepts the point, or redraws from {@code next} until a point is accepted. */
        void place(List<Integer> first, Supplier<List<Integer>> next) {
            var point = first;
            for (var redraws = 0; redraws < MAX_DRAWS_PER_PLACE; redraws++) {
                if (accept(point, redraws).isPresent()) {
                    return;
                }
                point = next.get();
            }
            throw new CandidateGenerationExhaustedException(MAX_DRAWS_PER_PLACE, accepted.size());
        }

        private Optional<Candidate> accept(List<Integer> point, int redraws) {
            return validation
                    .validate(grid.overridesOf(point))
                    .filter(valid -> digests.add(valid.effectiveRulesDigest()))
                    .map(valid -> {
                        var candidate = new Candidate(
                                Candidate.idOf(firstIndex + accepted.size()),
                                number,
                                point,
                                valid.overrides(),
                                valid.effectiveRulesDigest(),
                                redraws);
                        accepted.add(candidate);
                        return candidate;
                    });
        }
    }
}
