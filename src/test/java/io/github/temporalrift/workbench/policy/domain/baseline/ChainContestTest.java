package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.HandCard;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableCard;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;
import io.github.temporalrift.workbench.shared.game.CardGrade;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;
import io.github.temporalrift.workbench.shared.window.WindowKind;

/** A seat that is not the Weaver threatens a public chain on its way to completion at the outcome its Thread named. */
class ChainContestTest {

    private static final Target.EventOutcome PENDING =
            new Target.EventOutcome(PolicyFixtures.EVENT_A, PolicyFixtures.A_HIGH);

    private static final DealtCard SUPPRESS_I =
            new DealtCard(PolicyFixtures.id(0x2001), CardType.SUPPRESS, CardGrade.I, CardCategory.PROBABILITY_SHIFTER);
    private static final DealtCard SUPPRESS_II =
            new DealtCard(PolicyFixtures.id(0x2002), CardType.SUPPRESS, CardGrade.II, CardCategory.PROBABILITY_SHIFTER);

    private static Board chain(Board.ChainStatus status, int links, Board.PendingLink pending) {
        return new Board(List.of(), null, List.of(), new Board.ChainView(status, links, pending), List.of());
    }

    private static Board threatening() {
        return chain(Board.ChainStatus.ACTIVE, 2, new Board.PendingLink(PolicyFixtures.EVENT_A, PolicyFixtures.A_HIGH));
    }

    private static EntitledObservation round(Faction faction, Board board, List<PlayableSpecial> specials) {
        var cards = List.of(
                new PlayableCard(SUPPRESS_I, TargetShape.EVENT_OUTCOME, 1),
                new PlayableCard(SUPPRESS_II, TargetShape.EVENT_OUTCOME, 1));
        var hand = cards.stream().map(card -> new HandCard(card.card(), true)).toList();
        var window = new DecisionWindow.ActionRound(1, 2, hand, cards, specials);
        return new EntitledObservation(0, faction, PolicyFixtures.events(), PolicyFixtures.players(), window, board);
    }

    private static List<PlayableSpecial> annihilate() {
        return List.of(new PlayableSpecial(SpecialAction.ANNIHILATE, TargetShape.EVENT_OUTCOME));
    }

    @Test
    void anEraserAnnihilatesThePendingLink() {
        var chosen = ChainContest.choose(round(Faction.ERASERS, threatening(), annihilate()), Set.of());

        assertThat(chosen).contains(new Candidate.PlaySpecial(SpecialAction.ANNIHILATE, PENDING));
    }

    @Test
    void anyOtherSeatSuppressesItWithItsStrongestSuppress() {
        var chosen = ChainContest.choose(round(Faction.PROPHETS, threatening(), List.of()), Set.of());

        assertThat(chosen).contains(new Candidate.PlayCard(SUPPRESS_II.cardInstanceId(), PENDING));
    }

    @Test
    void anEraserWithoutAnAvailableAnnihilateSuppresses() {
        var unavailable = List.of(new PlayableSpecial(SpecialAction.ANNIHILATE, TargetShape.EVENT_OUTCOME, "jammed"));

        var chosen = ChainContest.choose(round(Faction.ERASERS, threatening(), unavailable), Set.of());

        assertThat(chosen).contains(new Candidate.PlayCard(SUPPRESS_II.cardInstanceId(), PENDING));
    }

    @Test
    void aRejectedPlayIsNotRepeated() {
        var rejected = Set.<Candidate>of(new Candidate.PlaySpecial(SpecialAction.ANNIHILATE, PENDING));

        var chosen = ChainContest.choose(round(Faction.ERASERS, threatening(), annihilate()), rejected);

        assertThat(chosen).contains(new Candidate.PlayCard(SUPPRESS_II.cardInstanceId(), PENDING));
    }

    @Test
    void theWeaverDoesNotContestItsOwnChain() {
        assertThat(ChainContest.choose(round(Faction.WEAVERS, threatening(), List.of()), Set.of()))
                .isEmpty();
    }

    @Test
    void aChainOfOneLinkIsNotYetAThreat() {
        var board = chain(
                Board.ChainStatus.ACTIVE, 1, new Board.PendingLink(PolicyFixtures.EVENT_A, PolicyFixtures.A_HIGH));

        assertThat(ChainContest.choose(round(Faction.ERASERS, board, annihilate()), Set.of()))
                .isEmpty();
    }

    @Test
    void aBrokenChainOrOneWithoutAPendingLinkIsLeftAlone() {
        var broken = chain(
                Board.ChainStatus.BROKEN, 3, new Board.PendingLink(PolicyFixtures.EVENT_A, PolicyFixtures.A_HIGH));
        var noPending = chain(Board.ChainStatus.ACTIVE, 3, null);

        assertThat(ChainContest.choose(round(Faction.ERASERS, broken, annihilate()), Set.of()))
                .isEmpty();
        assertThat(ChainContest.choose(round(Faction.ERASERS, noPending, annihilate()), Set.of()))
                .isEmpty();
    }

    @Test
    void aSeatDoesNotContestTheOutcomeItHasCommittedTo() {
        var written = new Board(
                List.of(),
                null,
                List.of(new Board.OwnSubmission(
                        WindowKind.ACTION_ROUND,
                        1,
                        null,
                        null,
                        SpecialAction.FORESIGHT,
                        PolicyFixtures.EVENT_A,
                        PolicyFixtures.A_HIGH,
                        null,
                        List.of(),
                        List.of())),
                threatening().chain(),
                List.of());

        assertThat(ChainContest.choose(round(Faction.PROPHETS, written, List.of()), Set.of()))
                .isEmpty();
    }

    @Test
    void outsideAnActionRoundThereIsNoContest() {
        var observation = PolicyFixtures.declaration(Faction.ERASERS);

        assertThat(ChainContest.choose(observation, Set.of())).isEmpty();
    }
}
