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

    private static final String RANDOM = "f4c74554f2c46eb16f2579aecf9af297337a8253d4336feba16ae8be803e0a70";
    private static final String FACTION_GREEDY = "1b559d9c75efe2e83897a6ebc79cfb7dc2814b305822cdbb2cac7ae4a9c06317";
    private static final String FACTION_SPREAD = "72768546a4e6648ee3262ac4cf1112e4798991f9751c9c8e2202a612d1d31628";

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
