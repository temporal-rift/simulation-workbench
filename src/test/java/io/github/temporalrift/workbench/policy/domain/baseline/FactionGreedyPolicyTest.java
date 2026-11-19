package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;
import io.github.temporalrift.workbench.shared.game.Faction;

class FactionGreedyPolicyTest {

    private final FactionGreedyPolicy greedy = new FactionGreedyPolicy();
    private final ParadoxAwarePolicy paradoxLayer = new ParadoxAwarePolicy();

    @Test
    void anUnavailableSpecialIsNeverChosen() {
        var plain = PolicyFixtures.actionRound(Faction.ERASERS);
        var allMarked = marked(plain, true);

        for (var seed = 0; seed < 50; seed++) {
            var chosen = choose(greedy, allMarked, seed);

            assertThat(chosen).isNotInstanceOf(Candidate.PlaySpecial.class);
        }
    }

    @Test
    void withoutAMarkItPlaysAsTheParadoxLayer() {
        var plain = PolicyFixtures.actionRound(Faction.ERASERS);

        for (var seed = 0; seed < 50; seed++) {
            assertThat(choose(greedy, plain, seed)).isEqualTo(choose(paradoxLayer, plain, seed));
        }
    }

    private static EntitledObservation marked(EntitledObservation observation, boolean unavailable) {
        var window = (DecisionWindow.ActionRound) observation.window();
        var specials = window.specials().stream()
                .map(special -> new PlayableSpecial(
                        special.action(), special.shape(), unavailable ? "NOT_ELIGIBLE_THIS_ROUND" : null))
                .toList();
        return new EntitledObservation(
                observation.seatIndex(),
                observation.faction(),
                observation.events(),
                observation.otherPlayerIds(),
                new DecisionWindow.ActionRound(window.era(), window.round(), window.hand(), window.cards(), specials),
                observation.board());
    }

    private static Candidate choose(BotPolicy policy, EntitledObservation observation, int seed) {
        var decision = policy.decide(
                observation,
                Set.of(),
                PolicyEntropy.derive(
                        seed, observation.seatIndex(), observation.window().ref(), 0));
        return ((PolicyDecision.Chosen) decision).candidate();
    }
}
