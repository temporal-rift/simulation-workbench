package io.github.temporalrift.workbench.search.domain.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateValidation;

class CandidateGeneratorTest {

    /** "a" lies in the middle of its grid, "b" at the lowest value, "c" at the highest. */
    private static final ParameterGrid GRID =
            new ParameterGrid(List.of(axis("a", 0, 10, 1, 5), axis("b", 0, 4, 1, 0), axis("c", 10, 20, 5, 20)));

    /** The space's one constraint: "a" may not exceed 8. */
    private static final CandidateValidation VALIDATION = overrides -> {
        var a = overrides.get("a");
        if (a != null && a.compareTo(BigDecimal.valueOf(8)) > 0) {
            return Optional.empty();
        }
        return Optional.of(
                new CandidateValidation.Validated("rules:" + new TreeMap<>(overrides), new TreeMap<>(overrides)));
    };

    @Test
    void roundZeroIsTheBaselineItsPerturbationsInKeyOrderAndASampleForTheRest() {
        var candidates = generator(20, "7").roundZero();

        assertThat(candidates).hasSize(20);
        assertThat(candidates.getFirst().overrides()).isEmpty();
        // "b" sits at the lowest value of its grid and "c" at the highest, so each has one perturbation only.
        assertThat(candidates.subList(1, 5))
                .extracting(candidate -> candidate.overrides().toString())
                .containsExactly("{a=4}", "{a=6}", "{b=1}", "{c=15}");
        assertThat(candidates.subList(5, 20))
                .allSatisfy(candidate -> assertThat(candidate.overrides()).isNotEmpty());
    }

    @Test
    void perturbationsThatFillTheRoundLeaveNoRoomForASample() {
        var candidates = generator(4, "7").roundZero();

        assertThat(candidates)
                .extracting(candidate -> candidate.overrides().toString())
                .containsExactly("{}", "{a=4}", "{a=6}", "{b=1}");
    }

    @Test
    void everyCandidateIsValidDistinctAndNumberedInGenerationOrder() {
        var candidates = generator(60, "7").roundZero();

        assertThat(candidates).hasSize(60);
        assertThat(candidates)
                .extracting(Candidate::id)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 60)
                        .mapToObj(Candidate::idOf)
                        .toList());
        assertThat(candidates).extracting(Candidate::effectiveRulesDigest).doesNotHaveDuplicates();
        assertThat(candidates)
                .allSatisfy(candidate ->
                        assertThat(VALIDATION.validate(candidate.overrides())).isPresent());
        assertThat(candidates)
                .allSatisfy(candidate -> assertThat(candidate.round()).isZero());
    }

    @Test
    void aDrawThatBreaksAConstraintIsRedrawnAndCountedNeverRepaired() {
        var candidates = generator(120, "7").roundZero();

        assertThat(candidates)
                .allSatisfy(candidate -> assertThat(candidate.overrides().getOrDefault("a", BigDecimal.ZERO))
                        .isLessThanOrEqualTo(BigDecimal.valueOf(8)));
        assertThat(candidates).extracting(Candidate::redraws).anyMatch(redraws -> redraws > 0);
    }

    @Test
    void theSameSeedGivesTheSameCandidatesInTheSameOrderAndAnotherSeedDoesNot() {
        var first = generator(40, "7").roundZero();
        var again = generator(40, "7").roundZero();
        var other = generator(40, "8").roundZero();

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void aLaterRoundMutatesSurvivorsByAFewStepsAndAddsRandomCandidates() {
        var generator = generator(32, "7");
        var earlier = generator.roundZero();
        var survivors = earlier.subList(0, 4);

        var added = generator.laterRound(1, survivors, earlier, earlier.size());

        assertThat(added).hasSize(generator.newCandidatesIn(1)).hasSize(8);
        assertThat(added).extracting(Candidate::round).containsOnly(1);
        assertThat(added.getFirst().id()).isEqualTo(Candidate.idOf(earlier.size()));
        var known = new HashSet<>(
                earlier.stream().map(Candidate::effectiveRulesDigest).toList());
        assertThat(added)
                .allSatisfy(candidate ->
                        assertThat(known.add(candidate.effectiveRulesDigest())).isTrue());
        var mutations = added.subList(0, added.size() - added.size() / CandidateGenerator.RANDOM_SHARE);
        for (var index = 0; index < mutations.size(); index++) {
            var parent = survivors.get(index % survivors.size()).point();
            var child = mutations.get(index).point();
            var moved = 0;
            for (var axis = 0; axis < child.size(); axis++) {
                var distance = Math.abs(child.get(axis) - parent.get(axis));
                assertThat(distance).isLessThanOrEqualTo(CandidateGenerator.maxMutationSteps(1));
                moved += distance == 0 ? 0 : 1;
            }
            assertThat(moved).isBetween(1, CandidateGenerator.MAX_MUTATED_PARAMETERS);
        }
    }

    @Test
    void mutationStepsShrinkEveryRoundButNeverBelowOne() {
        assertThat(CandidateGenerator.maxMutationSteps(1)).isEqualTo(4);
        assertThat(CandidateGenerator.maxMutationSteps(2)).isEqualTo(2);
        assertThat(CandidateGenerator.maxMutationSteps(3)).isEqualTo(1);
        assertThat(CandidateGenerator.maxMutationSteps(40)).isEqualTo(1);
    }

    @Test
    void theNumberOfNewCandidatesShrinksWithTheRoundsAndStaysAtLeastOne() {
        var generator = generator(32, "7");

        assertThat(generator.newCandidatesIn(1)).isEqualTo(8);
        assertThat(generator.newCandidatesIn(2)).isEqualTo(4);
        assertThat(generator.newCandidatesIn(3)).isEqualTo(2);
        assertThat(generator.newCandidatesIn(30)).isEqualTo(1);
    }

    @Test
    void aSpaceTooSmallToSupplyDistinctCandidatesFailsInsteadOfLooping() {
        var tiny = new ParameterGrid(List.of(axis("a", 0, 1, 1, 0)));
        var generator = new CandidateGenerator(tiny, VALIDATION, new SearchSeeds("7"), new SearchAlgorithm(5, 2, 2, 1));

        assertThatThrownBy(generator::roundZero).isInstanceOf(CandidateGenerationExhaustedException.class);
    }

    @Test
    void theGameSeedsOfASmallerRoundArePrefixesOfALargerOne() {
        var seeds = new SearchSeeds("18446744073709551615");

        var two = seeds.gameSeeds(2);
        var eight = seeds.gameSeeds(8);

        assertThat(eight.subList(0, 2)).isEqualTo(two);
        assertThat(new HashSet<>(eight)).hasSize(8);
        assertThat(new SearchSeeds("18446744073709551615").gameSeeds(8)).isEqualTo(eight);
        assertThat(new SearchSeeds("1").gameSeeds(8)).isNotEqualTo(eight);
        assertThat(eight)
                .allSatisfy(seed -> assertThat(Long.parseUnsignedLong(seed)).isNotNull());
    }

    @Test
    void eachRoundAndKindOfDrawHasItsOwnStream() {
        var seeds = new SearchSeeds("7");

        assertThat(seeds.stream(1, "mutation").nextLong())
                .isEqualTo(seeds.stream(1, "mutation").nextLong());
        assertThat(seeds.stream(1, "mutation").nextLong())
                .isNotEqualTo(seeds.stream(2, "mutation").nextLong());
        assertThat(seeds.stream(1, "mutation").nextLong())
                .isNotEqualTo(seeds.stream(1, "random").nextLong());
    }

    private static CandidateGenerator generator(int initialCandidates, String masterSeed) {
        return new CandidateGenerator(
                GRID, VALIDATION, new SearchSeeds(masterSeed), new SearchAlgorithm(initialCandidates, 2, 2, 1));
    }

    private static ParameterGrid.Axis axis(String key, int min, int max, int step, int baseline) {
        return new ParameterGrid.Axis(
                key,
                BigDecimal.valueOf(min),
                BigDecimal.valueOf(max),
                BigDecimal.valueOf(step),
                BigDecimal.valueOf(baseline));
    }
}
