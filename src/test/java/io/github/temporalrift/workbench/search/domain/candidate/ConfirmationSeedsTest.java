package io.github.temporalrift.workbench.search.domain.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfirmationSeedsTest {

    @Test
    void theSameMasterSeedAlwaysGivesTheSameSeeds() {
        assertThat(new ConfirmationSeeds("42").seeds(100)).isEqualTo(new ConfirmationSeeds("42").seeds(100));
    }

    @Test
    void theSeedsAreDistinctAndAsManyAsAsked() {
        assertThat(new ConfirmationSeeds("42").seeds(100)).hasSize(100).doesNotHaveDuplicates();
    }

    @Test
    void aSmallerCountIsAPrefixOfALargerOne() {
        var large = new ConfirmationSeeds("42").seeds(100);

        assertThat(new ConfirmationSeeds("42").seeds(10)).isEqualTo(large.subList(0, 10));
    }

    @Test
    void differentMasterSeedsGiveDifferentSeeds() {
        assertThat(new ConfirmationSeeds("42").seeds(100)).isNotEqualTo(new ConfirmationSeeds("43").seeds(100));
    }

    @Test
    void theSeedsOfAMasterSeedAreNotTheSearchSeedsOfTheSameValue() {
        assertThat(new ConfirmationSeeds("42").seeds(100))
                .doesNotContainAnyElementsOf(new SearchSeeds("42").gameSeeds(100));
    }
}
