package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;

/**
 * Pins what each baseline bundle decides over {@link PinnedDecisions}. When a hash here fails, the behavior of the
 * bundle changed: bump its revision in {@code BaselinePolicies} and record the new hash here in the same commit. A
 * layer shared by {@code faction-greedy} and {@code faction-spread} moves both hashes, so both revisions are bumped.
 */
class BundleBehaviorPinningTest {

    private static final String RANDOM = "fb6eaf99e7958a0fa0dc259163e92b7f7833d873a930efc59775bb9e0f412c77";
    private static final String FACTION_GREEDY = "4571e472c5e5f18349d61eb998a260e4481dfa45b97e906acc000404787c544e";
    private static final String FACTION_SPREAD = "976df303a90bed7d144d5cfb1ee1c0e305be07a03c41ea46957b029537596cd2";

    @Test
    void theCorpusKeepsItsSize() {
        assertThat(PinnedDecisions.corpus()).hasSize(PinnedDecisions.OBSERVATIONS);
    }

    @Test
    void theHashOfOnePolicyIsTheSameAcrossRuns() {
        var policy = new RandomPolicy();

        assertThat(PinnedDecisions.hashOf(policy)).isEqualTo(PinnedDecisions.hashOf(policy));
    }

    @Test
    void randomDecidesAsRecorded() {
        assertPinned(BaselinePolicies.RANDOM_ID, new RandomPolicy(), RANDOM);
    }

    @Test
    void factionGreedyDecidesAsRecorded() {
        assertPinned(BaselinePolicies.FACTION_GREEDY_ID, new FactionGreedyPolicy(), FACTION_GREEDY);
    }

    @Test
    void factionSpreadDecidesAsRecorded() {
        assertPinned(BaselinePolicies.FACTION_SPREAD_ID, new FactionSpreadPolicy(), FACTION_SPREAD);
    }

    private static void assertPinned(String id, BotPolicy policy, String recorded) {
        assertThat(PinnedDecisions.hashOf(policy))
                .as(
                        "%s decided differently over the pinned observations: bump its revision in BaselinePolicies and"
                                + " record this hash",
                        id)
                .isEqualTo(recorded);
    }
}
