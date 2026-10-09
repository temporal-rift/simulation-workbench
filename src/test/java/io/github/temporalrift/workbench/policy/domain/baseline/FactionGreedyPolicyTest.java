package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;

class FactionGreedyPolicyTest {

    private final FactionGreedyPolicy policy = new FactionGreedyPolicy();

    private Candidate decide(EntitledObservation observation, long seed) {
        var entropy = PolicyEntropy.derive(seed, 0, observation.window().key(), 0);
        return ((PolicyDecision.Chosen) policy.decide(observation, Set.of(), entropy)).candidate();
    }

    @Test
    void eraserAnnihilatesTheLeadingOutcome() {
        var choice = (Candidate.PlaySpecial) decide(PolicyFixtures.actionRound(Faction.ERASERS), 1);

        assertThat(choice.action()).isEqualTo(SpecialAction.ANNIHILATE);
        assertThat(((Target.EventOutcome) choice.target()).outcomeId().getLeastSignificantBits() & 0xF)
                .isEqualTo(3);
    }

    @Test
    void weaverThreadsTheLeadingOutcome() {
        var choice = (Candidate.PlaySpecial) decide(PolicyFixtures.actionRound(Faction.WEAVERS), 3);

        assertThat(choice.action()).isEqualTo(SpecialAction.THREAD);
        assertThat(((Target.EventOutcome) choice.target()).outcomeId().getLeastSignificantBits() & 0xF)
                .isEqualTo(3);
    }

    @Test
    void activistWithNoSpecialPlaysItsFavoredCard() {
        var observation = PolicyFixtures.actionRound(Faction.ACTIVISTS);
        var withoutSpecials = new EntitledObservation(
                0,
                Faction.ACTIVISTS,
                observation.events(),
                observation.otherPlayerIds(),
                new DecisionWindow.ActionRound(
                        1,
                        1,
                        ((DecisionWindow.ActionRound) observation.window()).hand(),
                        ((DecisionWindow.ActionRound) observation.window()).cards(),
                        List.of()));

        var choice = (Candidate.PlayCard) decide(withoutSpecials, 5);

        assertThat(choice.cardInstanceId())
                .isEqualTo(PolicyFixtures.card(1, CardType.PUSH).cardInstanceId());
        assertThat(choice.target()).isInstanceOf(Target.EventOutcome.class);
    }

    @Test
    void activistDeclaresALeadingOutcomeRatherThanDeclining() {
        var choice = decide(PolicyFixtures.declaration(Faction.ACTIVISTS), 9);

        assertThat(choice).isInstanceOf(Candidate.Declare.class);
        assertThat(((Candidate.Declare) choice).outcomeId().getLeastSignificantBits() & 0xF)
                .isEqualTo(3);
    }

    @Test
    void handSelectionKeepsTheHighestAffinityCards() {
        var choice = (Candidate.KeepHand) decide(PolicyFixtures.handSelection(Faction.ERASERS), 11);

        assertThat(choice.cardInstanceIds())
                .contains(
                        PolicyFixtures.card(2, CardType.SUPPRESS).cardInstanceId(),
                        PolicyFixtures.card(7, CardType.NULLIFY).cardInstanceId());
        assertThat(choice.cardInstanceIds())
                .doesNotContain(PolicyFixtures.card(4, CardType.AMPLIFY).cardInstanceId());
    }

    @Test
    void scannedWeightOutranksThePrintedWeight() {
        var scanned = new EventView(
                PolicyFixtures.EVENT_A,
                List.of(
                        new OutcomeView(PolicyFixtures.A_LOW, 20, 90),
                        new OutcomeView(PolicyFixtures.id(0xA2), 30, null),
                        new OutcomeView(PolicyFixtures.A_HIGH, 50, null)));
        var base = PolicyFixtures.actionRound(Faction.ERASERS);
        var observation =
                new EntitledObservation(0, Faction.ERASERS, List.of(scanned), base.otherPlayerIds(), base.window());

        var choice = (Candidate.PlaySpecial) decide(observation, 1);

        assertThat(choice.target()).isEqualTo(new Target.EventOutcome(PolicyFixtures.EVENT_A, PolicyFixtures.A_LOW));
    }

    @Test
    void tiesAreBrokenByTheSeedDeterministically() {
        var observation = PolicyFixtures.actionRound(Faction.ERASERS);
        var tied = new EntitledObservation(
                0,
                Faction.ERASERS,
                List.of(new EventView(
                        PolicyFixtures.EVENT_A,
                        List.of(
                                new OutcomeView(PolicyFixtures.id(0xA1), 50, null),
                                new OutcomeView(PolicyFixtures.id(0xA2), 50, null)))),
                observation.otherPlayerIds(),
                observation.window());

        var picks = java.util.stream.LongStream.range(0, 32)
                .mapToObj(seed -> decide(tied, seed))
                .distinct()
                .toList();

        assertThat(picks).hasSize(2);
        assertThat(decide(tied, 4)).isEqualTo(decide(tied, 4));
    }

    @Test
    void everyFactionHasAnExplicitOrDefaultPreferenceForEveryCardAndSpecial() {
        for (var faction : Faction.values()) {
            for (var type : CardType.values()) {
                assertThat(FactionPreferences.card(faction, type).affinity()).isPositive();
            }
            for (var action : SpecialAction.values()) {
                assertThat(FactionPreferences.special(faction, action).affinity())
                        .isPositive();
            }
        }
    }
}
