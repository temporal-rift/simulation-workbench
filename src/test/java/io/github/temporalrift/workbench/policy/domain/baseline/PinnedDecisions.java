package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.Band;
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
import io.github.temporalrift.workbench.policy.domain.observation.PublicBand;
import io.github.temporalrift.workbench.policy.domain.observation.TableRules;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;
import io.github.temporalrift.workbench.shared.game.CardGrade;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * The fixed observations and seeds every baseline bundle is played over to pin its behavior. A bundle's decisions over
 * this corpus are hashed and compared with a recorded value, so a change of behavior without a new behavior revision
 * fails its pinning test.
 *
 * <p>The corpus covers every faction in every window kind, three action rounds, and the board situations the bots
 * react to: an empty board, a public chain with a pending link, a stalled event with another seat's declaration, and
 * open paradoxes with a Detonate offered for a leading and a trailing seat. Adding a situation changes the corpus size
 * and so every recorded hash: add situations only together with new behavior, and re-record all three.
 */
final class PinnedDecisions {

    /** The number of observations in the corpus, so it cannot change without notice. */
    static final int OBSERVATIONS = 95;

    static final int SEEDS = 16;

    private static final UUID EVENT_1 = PolicyFixtures.id(0x101);
    private static final UUID EVENT_2 = PolicyFixtures.id(0x102);
    private static final UUID EVENT_3 = PolicyFixtures.id(0x103);
    private static final UUID OTHER_PLAYER = PolicyFixtures.id(0x02);

    private static final DealtCard DETONATE =
            new DealtCard(PolicyFixtures.id(0x3001), CardType.DETONATE, CardGrade.II, CardCategory.PARADOX);
    private static final DealtCard STABILIZE =
            new DealtCard(PolicyFixtures.id(0x3002), CardType.STABILIZE, CardGrade.II, CardCategory.PARADOX);

    private PinnedDecisions() {}

    /** A short digest of the decisions the policy takes over the whole corpus. */
    static String hashOf(BotPolicy policy) {
        var lines = new ArrayList<String>();
        var corpus = corpus();
        for (var index = 0; index < corpus.size(); index++) {
            var observation = corpus.get(index);
            for (var seed = 0; seed < SEEDS; seed++) {
                var entropy = PolicyEntropy.derive(
                        seed, observation.seatIndex(), observation.window().ref(), 0);
                var decision = policy.decide(observation, Set.of(), entropy);
                lines.add(index + "|" + seed + "|" + encode(decision));
            }
        }
        return Sha256.hexUtf8(String.join("\n", lines));
    }

    static List<EntitledObservation> corpus() {
        var corpus = new ArrayList<EntitledObservation>();
        for (var faction : Faction.values()) {
            corpus.addAll(PolicyFixtures.allWindows(faction));
            for (var round = 1; round <= 3; round++) {
                corpus.add(actionRound(faction, round, Board.EMPTY));
                corpus.add(actionRound(faction, round, chainBoard()));
                corpus.add(actionRound(faction, round, declaredBoard()));
            }
            corpus.add(paradox(faction, 2, true));
            corpus.add(paradox(faction, 3, true));
            corpus.add(paradox(faction, 2, false));
            corpus.add(paradox(faction, 3, false));
        }
        return corpus;
    }

    private static String encode(PolicyDecision decision) {
        return decision instanceof PolicyDecision.Chosen chosen
                ? CandidateCodec.encode(chosen.candidate())
                : "EXHAUSTED";
    }

    private static UUID outcome(UUID event, int index) {
        return new UUID(event.getLeastSignificantBits(), index + 1);
    }

    private static EventView event(UUID eventId, boolean stalled, int... weights) {
        var outcomes = new ArrayList<OutcomeView>();
        for (var index = 0; index < weights.length; index++) {
            var band = index == 0
                    ? new PublicBand(Band.HIGH, 1)
                    : index == weights.length - 1 ? new PublicBand(Band.LOW, 1) : null;
            outcomes.add(new OutcomeView(outcome(eventId, index), weights[index], null, band));
        }
        return new EventView(eventId, outcomes, stalled);
    }

    private static List<EventView> events(boolean stallThird) {
        return List.of(
                event(EVENT_1, false, 60, 25, 10),
                event(EVENT_2, false, 50, 30, 20),
                event(EVENT_3, stallThird, 34, 33, 20));
    }

    private static EntitledObservation actionRound(Faction faction, int round, Board board) {
        var cards = List.of(
                new PlayableCard(PolicyFixtures.card(1, CardType.PUSH), TargetShape.EVENT_OUTCOME, 1),
                new PlayableCard(PolicyFixtures.card(2, CardType.SWING), TargetShape.OUTCOME_PAIR, 1),
                new PlayableCard(PolicyFixtures.card(3, CardType.SCAN), TargetShape.EVENT_LIST, 2));
        var hand = cards.stream().map(card -> new HandCard(card.card(), true)).toList();
        var window = new DecisionWindow.ActionRound(1, round, hand, cards, PolicyFixtures.specialsOf(faction));
        var stalled = board.declarations().size() > 0;
        return new EntitledObservation(0, faction, events(stalled), PolicyFixtures.players(), window, board);
    }

    /** A public chain of two confirmed links with a pending link on event 2. */
    private static Board chainBoard() {
        var chain = new Board.ChainView(
                Board.ChainStatus.ACTIVE,
                2,
                new Board.PendingLink(EVENT_2, outcome(EVENT_2, 1)),
                List.of(EVENT_1, EVENT_3));
        return new Board(List.of(), null, List.of(), chain, List.of(), List.of(), null);
    }

    /** Another seat's public declaration and a stalled third event. */
    private static Board declaredBoard() {
        var declaration =
                new Board.PublicDeclaration(OTHER_PLAYER, DeclarationMode.RALLY, EVENT_1, outcome(EVENT_1, 0));
        return new Board(List.of(declaration), null, List.of(), null, List.of(), List.of(), null);
    }

    /** A Chain Conflict open on event 2 with a Detonate offered, for a seat that leads or trails. */
    private static EntitledObservation paradox(Faction faction, int confirmedLinks, boolean leads) {
        var chain = new Board.ChainView(Board.ChainStatus.ACTIVE, confirmedLinks);
        var scores = leads ? new Board.Scores(8, List.of(5, 3, 2)) : new Board.Scores(2, List.of(8, 5, 3));
        var board = new Board(
                List.of(),
                null,
                List.of(),
                chain,
                List.of(new Board.OpenParadox(Board.ParadoxKind.CHAIN_CONFLICT, EVENT_2, List.of())),
                List.of(EVENT_1, EVENT_3),
                scores);
        var window = new DecisionWindow.ParadoxResolution(1, List.of(DETONATE, STABILIZE));
        var affected = List.of(event(EVENT_2, false, 50, 30, 20));
        return new EntitledObservation(0, faction, affected, PolicyFixtures.players(), window, board)
                .withRules(new TableRules(true, 0, -1, -2, false, 4, 3));
    }
}
