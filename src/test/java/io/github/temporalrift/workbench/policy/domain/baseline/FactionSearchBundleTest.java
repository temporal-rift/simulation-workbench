package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;

class FactionSearchBundleTest {

    @Test
    void defaultsDecideAsFactionSpreadOverThePinnedObservations() {
        assertThat(PinnedDecisions.hashOf(FactionSearchBundle.defaults().policy()))
                .isEqualTo(PinnedDecisions.hashOf(BaselinePolicies.FACTION_SPREAD.policy()));
    }

    @Test
    void defaultsLeaveTheEntropyStreamWhereFactionSpreadLeavesIt() {
        var spread = BaselinePolicies.FACTION_SPREAD.policy();
        var search = FactionSearchBundle.defaults().policy();
        for (var observation : PinnedDecisions.corpus()) {
            for (var seed = 0; seed < PinnedDecisions.SEEDS; seed++) {
                var expected = entropyOf(observation, seed);
                var actual = entropyOf(observation, seed);
                assertThat(search.decide(observation, Set.of(), actual))
                        .isEqualTo(spread.decide(observation, Set.of(), expected));
                assertThat(actual.nextIndex(1_000_000)).isEqualTo(expected.nextIndex(1_000_000));
            }
        }
    }

    @Test
    void aMovedParameterChangesWhatIsDecided() {
        var moved = FactionSearchBundle.of(Map.of(
                SearchParameters.cardKey(Faction.PROPHETS, CardType.SCAN), 8,
                SearchParameters.cardKey(Faction.PROPHETS, CardType.PUSH), 0));

        assertThat(PinnedDecisions.hashOf(moved.policy()))
                .isNotEqualTo(PinnedDecisions.hashOf(BaselinePolicies.FACTION_SPREAD.policy()));
    }

    @Test
    void equalParametersGiveTheSameDigestWhetherOrNotADefaultIsNamed() {
        var key = SearchParameters.cardKey(Faction.WEAVERS, CardType.PUSH);
        var defaultValue = SearchParameters.spec(key).orElseThrow().defaultValue();

        assertThat(FactionSearchBundle.of(Map.of(key, defaultValue)).artifactDigest())
                .isEqualTo(FactionSearchBundle.defaults().artifactDigest());
    }

    @Test
    void anotherValueGivesAnotherDigest() {
        var key = SearchParameters.cardKey(Faction.WEAVERS, CardType.SCAN);

        assertThat(FactionSearchBundle.of(Map.of(key, 8)).artifactDigest())
                .isNotEqualTo(FactionSearchBundle.defaults().artifactDigest());
    }

    @Test
    void anUnknownOrOffGridParameterIsRefusedByName() {
        assertThatThrownBy(() -> FactionSearchBundle.of(Map.of("nope", 1)))
                .isInstanceOfSatisfying(
                        SearchParameters.Refused.class,
                        refused -> assertThat(refused.parameter()).isEqualTo("nope"));
        assertThatThrownBy(() -> FactionSearchBundle.of(
                        Map.of(SearchParameters.scoped(SearchParameters.RUNNER_UP_PERCENT, Faction.PROPHETS), 30)))
                .isInstanceOf(SearchParameters.Refused.class);
        assertThatThrownBy(() -> FactionSearchBundle.of(
                        Map.of(SearchParameters.scoped(SearchParameters.BET_SPREADING, Faction.PROPHETS), 2)))
                .isInstanceOf(SearchParameters.Refused.class);
    }

    @Test
    void everyDefaultLiesOnItsOwnGrid() {
        assertThat(SearchParameters.schema())
                .allSatisfy(spec -> assertThat(spec.accepts(spec.defaultValue()))
                        .as(spec.key())
                        .isTrue());
    }

    private static PolicyEntropy entropyOf(EntitledObservation observation, int seed) {
        return PolicyEntropy.derive(
                seed, observation.seatIndex(), observation.window().ref(), 0);
    }

    @Test
    void aFactionsParametersChangeOnlyThePlayOfThatFaction() {
        var weavers = FactionSearchBundle.of(Map.of(
                SearchParameters.cardKey(Faction.WEAVERS, CardType.SCAN), 8,
                SearchParameters.scoped(SearchParameters.TAPESTRY_LINKS, Faction.WEAVERS), 4,
                SearchParameters.scoped(SearchParameters.BET_SPREADING, Faction.WEAVERS), 0,
                SearchParameters.scoped(SearchParameters.CONTEST_CHAIN, Faction.WEAVERS), 0));
        var spread = BaselinePolicies.FACTION_SPREAD.policy();
        var others = PinnedDecisions.corpus().stream()
                .filter(observation -> observation.faction() != Faction.WEAVERS)
                .toList();

        assertThat(others).isNotEmpty();
        for (var observation : others) {
            for (var seed = 0; seed < PinnedDecisions.SEEDS; seed++) {
                assertThat(weavers.policy().decide(observation, Set.of(), entropyOf(observation, seed)))
                        .isEqualTo(spread.decide(observation, Set.of(), entropyOf(observation, seed)));
            }
        }
    }
}
