package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.HandCard;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableCard;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;
import io.github.temporalrift.workbench.policy.domain.observation.TableRules;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;
import io.github.temporalrift.workbench.shared.game.CardGrade;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

class FactionSpreadPolicyTest {

    private static final int DRAWS = 10_000;
    private static final double TOLERANCE = 0.02;

    private static final UUID EVENT_1 = PolicyFixtures.id(0x101);
    private static final UUID EVENT_2 = PolicyFixtures.id(0x102);
    private static final UUID EVENT_3 = PolicyFixtures.id(0x103);

    private final FactionSpreadPolicy spread = new FactionSpreadPolicy();
    private final FactionGreedyPolicy greedy = new FactionGreedyPolicy();

    @Test
    void aRewriteIsSpreadOverEventsByTheirFavoritesWeight() {
        var observation =
                round(Faction.REVISIONISTS, threeEvents(), Board.EMPTY, List.of(special(SpecialAction.REWRITE)));

        var shares = shares(spread, observation);

        assertThat(shares.get(EVENT_1)).isCloseTo(60 / 144.0, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(shares.get(EVENT_2)).isCloseTo(50 / 144.0, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(shares.get(EVENT_3)).isCloseTo(34 / 144.0, org.assertj.core.data.Offset.offset(TOLERANCE));
    }

    @Test
    void aRewriteNamesTheFavoriteOfTheDrawnEvent() {
        var observation =
                round(Faction.REVISIONISTS, threeEvents(), Board.EMPTY, List.of(special(SpecialAction.REWRITE)));
        var favorites = Set.of(outcome(EVENT_1, 0), outcome(EVENT_2, 0), outcome(EVENT_3, 0));

        for (var seed = 0; seed < 300; seed++) {
            var target = (Target.EventOutcome) ((Candidate.PlaySpecial) choose(spread, observation, seed)).target();

            assertThat(favorites).contains(target.outcomeId());
        }
    }

    @Test
    void greedyStillRewritesTheTopOutcome() {
        var observation =
                round(Faction.REVISIONISTS, threeEvents(), Board.EMPTY, List.of(special(SpecialAction.REWRITE)));

        for (var seed = 0; seed < 300; seed++) {
            var target = (Target.EventOutcome) ((Candidate.PlaySpecial) choose(greedy, observation, seed)).target();

            assertThat(target.outcomeId()).isEqualTo(outcome(EVENT_1, 0));
        }
    }

    @Test
    void aProphetWritesTheFavoriteHalfTheTimeAndTheRunnerUpOtherwise() {
        var events = List.of(event(EVENT_1, 50, 30, 20));
        var observation = round(Faction.PROPHETS, events, Board.EMPTY, List.of(special(SpecialAction.FORESIGHT)));
        var written = new HashMap<UUID, Integer>();

        for (var seed = 0; seed < DRAWS; seed++) {
            var target = (Target.EventOutcome) ((Candidate.PlaySpecial) choose(spread, observation, seed)).target();
            written.merge(target.outcomeId(), 1, Integer::sum);
        }

        assertThat(written.get(outcome(EVENT_1, 0)) / (double) DRAWS)
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(written.get(outcome(EVENT_1, 1)) / (double) DRAWS)
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(written).doesNotContainKey(outcome(EVENT_1, 2));
    }

    @Test
    void anEraserAnnihilatesThePendingOutcomeOfAPublicChainOfTwoLinks() {
        var pending = new Board.PendingLink(EVENT_3, outcome(EVENT_3, 1));
        var board = new Board(
                List.of(), null, List.of(), new Board.ChainView(Board.ChainStatus.ACTIVE, 2, pending), List.of());
        var observation = round(Faction.ERASERS, threeEvents(), board, List.of(special(SpecialAction.ANNIHILATE)));

        for (var seed = 0; seed < 100; seed++) {
            assertThat(choose(spread, observation, seed))
                    .isEqualTo(new Candidate.PlaySpecial(
                            SpecialAction.ANNIHILATE, new Target.EventOutcome(EVENT_3, outcome(EVENT_3, 1))));
        }
    }

    @Test
    void anEraserWithoutAChainAnnihilatesADrawnFavorite() {
        var observation =
                round(Faction.ERASERS, threeEvents(), Board.EMPTY, List.of(special(SpecialAction.ANNIHILATE)));

        var shares = shares(spread, observation);

        assertThat(shares.get(EVENT_1)).isCloseTo(60 / 144.0, org.assertj.core.data.Offset.offset(TOLERANCE));
        assertThat(shares.get(EVENT_3)).isCloseTo(34 / 144.0, org.assertj.core.data.Offset.offset(TOLERANCE));
    }

    @Test
    void aStalledEventIsNeverDrawn() {
        var events = new ArrayList<>(threeEvents());
        events.set(0, new EventView(EVENT_1, events.get(0).outcomes(), true));
        var observation = round(Faction.REVISIONISTS, events, Board.EMPTY, List.of(special(SpecialAction.REWRITE)));

        for (var seed = 0; seed < 500; seed++) {
            var target = (Target.EventOutcome) ((Candidate.PlaySpecial) choose(spread, observation, seed)).target();

            assertThat(target.eventId()).isNotEqualTo(EVENT_1);
        }
    }

    @Test
    void aWeaverDoesNotThreadAnEventThatIsAlreadyAConfirmedLink() {
        var board = new Board(
                List.of(),
                null,
                List.of(),
                new Board.ChainView(Board.ChainStatus.ACTIVE, 1, null, List.of(EVENT_1)),
                List.of());
        var observation = round(Faction.WEAVERS, threeEvents(), board, List.of(special(SpecialAction.THREAD)));

        for (var seed = 0; seed < 500; seed++) {
            var target = (Target.EventOutcome) ((Candidate.PlaySpecial) choose(spread, observation, seed)).target();

            assertThat(target.eventId()).isNotEqualTo(EVENT_1);
        }
    }

    @Test
    void aRefusedEventIsNotDrawnAgain() {
        var observation =
                round(Faction.REVISIONISTS, threeEvents(), Board.EMPTY, List.of(special(SpecialAction.REWRITE)));
        var refused =
                new Candidate.PlaySpecial(SpecialAction.REWRITE, new Target.EventOutcome(EVENT_1, outcome(EVENT_1, 0)));

        for (var seed = 0; seed < 300; seed++) {
            var decision = spread.decide(observation, Set.of(refused), entropy(observation, seed));
            var target = (Target.EventOutcome)
                    ((Candidate.PlaySpecial) ((PolicyDecision.Chosen) decision).candidate()).target();

            assertThat(target.eventId()).isNotEqualTo(EVENT_1);
        }
    }

    @Test
    void aDeclarationIsSpreadOverEventsToo() {
        var observation = new EntitledObservation(
                0,
                Faction.ACTIVISTS,
                threeEvents(),
                PolicyFixtures.players(),
                new DecisionWindow.Declaration(1, List.of(DeclarationMode.RALLY)));
        var seen = new HashMap<UUID, Integer>();

        for (var seed = 0; seed < 1_000; seed++) {
            var declare = (Candidate.Declare) choose(spread, observation, seed);
            seen.merge(declare.eventId(), 1, Integer::sum);
            assertThat(declare.outcomeId()).isEqualTo(outcome(declare.eventId(), 0));
        }

        assertThat(seen).containsKeys(EVENT_1, EVENT_2, EVENT_3);
    }

    @Test
    void aSeatDoesNotPushThePublicPendingLinkOfAnotherFaction() {
        var tied = List.of(event(EVENT_1, 40, 40, 20));
        var pending = new Board.PendingLink(EVENT_1, outcome(EVENT_1, 0));
        var board = new Board(
                List.of(), null, List.of(), new Board.ChainView(Board.ChainStatus.ACTIVE, 1, pending), List.of());
        var push = PolicyFixtures.card(1, CardType.PUSH);
        var observation = withCards(Faction.REVISIONISTS, tied, board, List.of(push));
        var greedyPushedIt = false;

        for (var seed = 0; seed < 200; seed++) {
            var spreadTarget = ((Candidate.PlayCard) choose(spread, observation, seed)).target();
            assertThat(spreadTarget).isEqualTo(new Target.EventOutcome(EVENT_1, outcome(EVENT_1, 1)));
            greedyPushedIt |= ((Candidate.PlayCard) choose(greedy, observation, seed))
                    .target()
                    .equals(new Target.EventOutcome(EVENT_1, outcome(EVENT_1, 0)));
        }

        assertThat(greedyPushedIt).isTrue();
    }

    @Test
    void aWeaverMayPushItsOwnPendingLink() {
        var tied = List.of(event(EVENT_1, 40, 40, 20));
        var pending = new Board.PendingLink(EVENT_1, outcome(EVENT_1, 0));
        var board = new Board(
                List.of(), null, List.of(), new Board.ChainView(Board.ChainStatus.ACTIVE, 1, pending), List.of());
        var observation = withCards(Faction.WEAVERS, tied, board, List.of(PolicyFixtures.card(1, CardType.PUSH)));
        var pushedIt = false;

        for (var seed = 0; seed < 200; seed++) {
            pushedIt |= ((Candidate.PlayCard) choose(spread, observation, seed))
                    .target()
                    .equals(new Target.EventOutcome(EVENT_1, outcome(EVENT_1, 0)));
        }

        assertThat(pushedIt).isTrue();
    }

    @Test
    void anActivistDetonatesAChainConflictWhenTheChainIsTwoLinksFromCompleting() {
        var observation = paradox(Faction.ACTIVISTS, chain(2), null, Board.ParadoxKind.CHAIN_CONFLICT, 4);

        assertThat(choose(spread, observation, 1)).isEqualTo(detonate(EVENT_2));
    }

    @Test
    void aShorterChainFollowsTheExistingLayer() {
        var observation = paradox(Faction.ACTIVISTS, chain(1), null, Board.ParadoxKind.CHAIN_CONFLICT, 4);

        assertThat(choose(spread, observation, 1)).isNotEqualTo(detonate(EVENT_2));
        assertThat(choose(spread, observation, 1)).isEqualTo(choose(greedy, observation, 1));
    }

    @Test
    void aWeaverNeverBreaksItsOwnChain() {
        var observation = paradox(Faction.WEAVERS, chain(2), null, Board.ParadoxKind.CHAIN_CONFLICT, 4);

        assertThat(choose(spread, observation, 1)).isNotEqualTo(detonate(EVENT_2));
    }

    @Test
    void theLeaderFinishesACollapse() {
        var scores = new Board.Scores(9, List.of(4, 7));
        var observation = paradox(Faction.REVISIONISTS, null, scores, Board.ParadoxKind.DEAD_HEAT, 4, EVENT_1, EVENT_3);

        assertThat(choose(spread, observation, 1)).isEqualTo(detonate(EVENT_2));
    }

    @Test
    void aTrailingSeatDoesNotFinishACollapse() {
        var scores = new Board.Scores(5, List.of(4, 7));
        var observation = paradox(Faction.REVISIONISTS, null, scores, Board.ParadoxKind.DEAD_HEAT, 4, EVENT_1, EVENT_3);

        assertThat(choose(spread, observation, 1)).isNotEqualTo(detonate(EVENT_2));
    }

    @Test
    void changingTheLeaderChangesSpreadButNotGreedyAtAnImminentCollapse() {
        var leading = paradox(
                Faction.REVISIONISTS,
                null,
                new Board.Scores(9, List.of(4, 7)),
                Board.ParadoxKind.DEAD_HEAT,
                4,
                EVENT_1,
                EVENT_3);
        var trailing = paradox(
                Faction.REVISIONISTS,
                null,
                new Board.Scores(5, List.of(4, 7)),
                Board.ParadoxKind.DEAD_HEAT,
                4,
                EVENT_1,
                EVENT_3);

        assertThat(choose(spread, leading, 1)).isEqualTo(detonate(EVENT_2));
        assertThat(choose(spread, trailing, 1)).isNotEqualTo(choose(spread, leading, 1));
        assertThat(choose(greedy, trailing, 1)).isEqualTo(choose(greedy, leading, 1));
    }

    @Test
    void aLeadingChainIsDetonatedRegardlessOfLeaderAtAnImminentCollapse() {
        var leading = paradox(
                Faction.ACTIVISTS,
                chain(2),
                new Board.Scores(9, List.of(4, 7)),
                Board.ParadoxKind.CHAIN_CONFLICT,
                4,
                EVENT_1,
                EVENT_3);
        var trailing = paradox(
                Faction.ACTIVISTS,
                chain(2),
                new Board.Scores(5, List.of(4, 7)),
                Board.ParadoxKind.CHAIN_CONFLICT,
                4,
                EVENT_1,
                EVENT_3);

        assertThat(choose(spread, leading, 1)).isEqualTo(detonate(EVENT_2));
        assertThat(choose(spread, trailing, 1)).isEqualTo(detonate(EVENT_2));
        assertThat(choose(greedy, trailing, 1)).isEqualTo(choose(greedy, leading, 1));
    }

    @Test
    void theLeaderDoesNotDetonateWhenTheCollapseIsStillFar() {
        var scores = new Board.Scores(9, List.of(4, 7));
        var observation = paradox(Faction.REVISIONISTS, null, scores, Board.ParadoxKind.DEAD_HEAT, 4, EVENT_1);

        assertThat(choose(spread, observation, 1)).isNotEqualTo(detonate(EVENT_2));
    }

    @Test
    void equalSeedsAndObservationsGiveEqualDecisions() {
        var observation =
                round(Faction.REVISIONISTS, threeEvents(), Board.EMPTY, List.of(special(SpecialAction.REWRITE)));

        for (var seed = 0; seed < 50; seed++) {
            assertThat(choose(spread, observation, seed)).isEqualTo(choose(spread, observation, seed));
        }
    }

    private static Map<UUID, Double> shares(BotPolicy policy, EntitledObservation observation) {
        var counts = new HashMap<UUID, Integer>();
        for (var seed = 0; seed < DRAWS; seed++) {
            var target = (Target.EventOutcome) ((Candidate.PlaySpecial) choose(policy, observation, seed)).target();
            counts.merge(target.eventId(), 1, Integer::sum);
        }
        var shares = new HashMap<UUID, Double>();
        counts.forEach((event, count) -> shares.put(event, count / (double) DRAWS));
        return shares;
    }

    private static PlayableSpecial special(SpecialAction action) {
        return new PlayableSpecial(action, TargetShape.EVENT_OUTCOME);
    }

    private static UUID outcome(UUID event, int index) {
        return new UUID(event.getLeastSignificantBits(), index + 1);
    }

    private static EventView event(UUID eventId, int... weights) {
        var outcomes = new ArrayList<OutcomeView>();
        for (var index = 0; index < weights.length; index++) {
            outcomes.add(new OutcomeView(outcome(eventId, index), weights[index], null));
        }
        return new EventView(eventId, outcomes);
    }

    /** Three events whose favorites weigh 60, 50 and 34. */
    private static List<EventView> threeEvents() {
        return List.of(event(EVENT_1, 60, 25, 10), event(EVENT_2, 50, 30, 20), event(EVENT_3, 34, 33, 20));
    }

    private static EntitledObservation round(
            Faction faction, List<EventView> events, Board board, List<PlayableSpecial> specials) {
        var window = new DecisionWindow.ActionRound(1, 1, List.of(), List.of(), specials);
        return new EntitledObservation(0, faction, events, PolicyFixtures.players(), window, board);
    }

    private static EntitledObservation withCards(
            Faction faction, List<EventView> events, Board board, List<DealtCard> dealt) {
        var cards = dealt.stream()
                .map(card -> new PlayableCard(card, TargetShape.EVENT_OUTCOME, 1))
                .toList();
        var hand = cards.stream().map(card -> new HandCard(card.card(), true)).toList();
        var window = new DecisionWindow.ActionRound(1, 1, hand, cards, List.of());
        return new EntitledObservation(0, faction, events, PolicyFixtures.players(), window, board);
    }

    private static Board chain(int confirmedLinks) {
        return new Board(
                List.of(),
                null,
                List.of(),
                new Board.ChainView(Board.ChainStatus.ACTIVE, confirmedLinks),
                List.of(new Board.OpenParadox(Board.ParadoxKind.CHAIN_CONFLICT, EVENT_2, List.of())));
    }

    private static final DealtCard DETONATE =
            new DealtCard(PolicyFixtures.id(0x3001), CardType.DETONATE, CardGrade.II, CardCategory.PARADOX);

    /** A paradox open on event 2 with a Detonate offered; the table chain length is as given. */
    private static EntitledObservation paradox(
            Faction faction,
            Board chainBoard,
            Board.Scores scores,
            Board.ParadoxKind kind,
            int chainLength,
            UUID... cascaded) {
        var base = chainBoard == null
                ? new Board(List.of(), null, List.of(), null, List.of(new Board.OpenParadox(kind, EVENT_2, List.of())))
                : chainBoard;
        var board = new Board(
                base.declarations(),
                base.lastRoundSummary(),
                base.ownSubmissions(),
                base.chain(),
                base.openParadoxes(),
                List.of(cascaded),
                scores);
        var window = new DecisionWindow.ParadoxResolution(1, List.of(DETONATE));
        var rules = new TableRules(true, 0, -1, -2, false, chainLength, 3);
        var affected = threeEvents().stream()
                .filter(event -> event.eventId().equals(EVENT_2))
                .toList();
        return new EntitledObservation(0, faction, affected, PolicyFixtures.players(), window, board).withRules(rules);
    }

    private static Candidate detonate(UUID event) {
        return new Candidate.PlayParadoxCard(
                DETONATE.cardInstanceId(), new Target.EventOutcome(event, outcome(event, 0)));
    }

    private static PolicyEntropy entropy(EntitledObservation observation, int seed) {
        return PolicyEntropy.derive(
                seed, observation.seatIndex(), observation.window().ref(), 0);
    }

    private static Candidate choose(BotPolicy policy, EntitledObservation observation, int seed) {
        var decision = policy.decide(observation, Set.of(), entropy(observation, seed));
        return ((PolicyDecision.Chosen) decision).candidate();
    }
}
