package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.Band;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;
import io.github.temporalrift.workbench.policy.domain.observation.PublicBand;
import io.github.temporalrift.workbench.policy.domain.observation.TableRules;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;
import io.github.temporalrift.workbench.shared.window.WindowKind;

class FactionGreedyV3PolicyTest {

    private static final UUID EVENT_A = PolicyFixtures.EVENT_A;
    private static final UUID A3 = PolicyFixtures.id(0xA3);
    private static final UUID B3 = PolicyFixtures.id(0xB3);
    private static final UUID C3 = PolicyFixtures.id(0xC3);
    private static final DealtCard STABILIZE = PolicyFixtures.card(1, CardType.STABILIZE);
    private static final DealtCard DETONATE = PolicyFixtures.card(2, CardType.DETONATE);
    private static final DealtCard PUSH = PolicyFixtures.card(3, CardType.PUSH);
    private static final TableRules CONTESTED = TableRules.DEFAULTS;
    private static final TableRules UNCONTESTED = new TableRules(false, -1, -1, -2, false);

    private final FactionGreedyV3Policy policy = new FactionGreedyV3Policy();

    @Test
    void aSeatStabilizesAParadoxOnItsOwnCommitment() {
        var chosen = choose(paradox(Faction.WEAVERS, threadOnA(), CONTESTED));

        assertThat(chosen).isEqualTo(play(STABILIZE, EVENT_A));
    }

    @Test
    void aSeatWithNoStakeNeverPlaysStabilizeOrDetonateByHabit() {
        var chosen = choose(paradox(Faction.PROPHETS, Board.EMPTY, CONTESTED));

        assertThat(chosen).isNotEqualTo(play(STABILIZE, EVENT_A)).isNotEqualTo(play(DETONATE, EVENT_A));
    }

    @Test
    void withoutTheContestASeatWithNoStakeDetonatesSoTheOthersPayDouble() {
        var chosen = choose(paradox(Faction.ERASERS, Board.EMPTY, UNCONTESTED));

        assertThat(chosen).isEqualTo(play(DETONATE, EVENT_A));
    }

    @Test
    void aStabilizeCostingMoreThanTheStakeIsNotPlayed() {
        var costly = new TableRules(true, -5, -1, -2, false);

        var chosen = choose(paradox(Faction.WEAVERS, threadOnA(), costly));

        assertThat(chosen).isNotEqualTo(play(STABILIZE, EVENT_A)).isNotEqualTo(play(DETONATE, EVENT_A));
    }

    @Test
    void underTheCurrentTapestryRuleAWeaverReweavesInsteadOfThreadingAgain() {
        var board = new Board(
                List.of(),
                null,
                List.of(special(1, SpecialAction.THREAD, EVENT_A, A3), special(2, SpecialAction.TAPESTRY, EVENT_A, A3)),
                new Board.ChainView(Board.ChainStatus.ACTIVE, 2),
                List.of());
        var specials = List.of(
                new PlayableSpecial(SpecialAction.THREAD, TargetShape.EVENT_OUTCOME),
                new PlayableSpecial(SpecialAction.TAPESTRY, TargetShape.EVENT_OUTCOME),
                new PlayableSpecial(SpecialAction.REWEAVE, TargetShape.EVENT_OUTCOME));
        var window = new DecisionWindow.ActionRound(1, 3, List.of(), List.of(), specials);
        var bands = Map.of(A3, Band.LOW, B3, Band.HIGH, C3, Band.MEDIUM);
        var observation = new EntitledObservation(
                        0, Faction.WEAVERS, events(bands), PolicyFixtures.players(), window, board)
                .withRules(CONTESTED);

        var chosen = choose(observation);

        assertThat(chosen)
                .isInstanceOfSatisfying(
                        Candidate.PlaySpecial.class,
                        special -> assertThat(special.action()).isEqualTo(SpecialAction.REWEAVE));
    }

    @Test
    void theV2BundleKeepsItsDigestAndTheV3BundleIsServed() {
        assertThat(BaselinePolicies.find(BaselinePolicies.FACTION_GREEDY_ID, BaselinePolicies.V3))
                .hasValue(BaselinePolicies.FACTION_GREEDY_V3);
        assertThat(BaselinePolicies.FACTION_GREEDY_V3.artifactDigest())
                .isNotEqualTo(BaselinePolicies.FACTION_GREEDY_V2.artifactDigest());
    }

    private Candidate choose(EntitledObservation observation) {
        var decision = policy.decide(
                observation,
                Set.of(),
                PolicyEntropy.derive(
                        7, observation.seatIndex(), observation.window().ref(), 0));
        return ((PolicyDecision.Chosen) decision).candidate();
    }

    /** A paradox open on event A only, with a Stabilize, a Detonate and a Push offered. */
    private static EntitledObservation paradox(Faction faction, Board board, TableRules rules) {
        var affected = events(Map.of()).stream()
                .filter(event -> event.eventId().equals(EVENT_A))
                .toList();
        var window = new DecisionWindow.ParadoxResolution(1, List.of(STABILIZE, DETONATE, PUSH));
        return new EntitledObservation(0, faction, affected, PolicyFixtures.players(), window, board).withRules(rules);
    }

    private static Board threadOnA() {
        return new Board(
                List.of(),
                null,
                List.of(special(1, SpecialAction.THREAD, EVENT_A, A3)),
                new Board.ChainView(Board.ChainStatus.ACTIVE, 1),
                List.of());
    }

    private static Candidate play(DealtCard card, UUID eventId) {
        var event = events(Map.of()).stream()
                .filter(view -> view.eventId().equals(eventId))
                .findFirst()
                .orElseThrow();
        return new Candidate.PlayParadoxCard(
                card.cardInstanceId(),
                new Target.EventOutcome(eventId, event.outcomes().getFirst().outcomeId()));
    }

    private static Board.OwnSubmission special(int round, SpecialAction action, UUID eventId, UUID outcomeId) {
        return new Board.OwnSubmission(
                WindowKind.ACTION_ROUND, round, null, null, action, eventId, outcomeId, null, List.of(), List.of());
    }

    private static List<EventView> events(Map<UUID, Band> bands) {
        var events = new ArrayList<EventView>();
        for (var event : PolicyFixtures.events()) {
            events.add(new EventView(
                    event.eventId(),
                    event.outcomes().stream()
                            .map(outcome -> new OutcomeView(
                                    outcome.outcomeId(),
                                    outcome.printedWeight(),
                                    null,
                                    bands.containsKey(outcome.outcomeId())
                                            ? new PublicBand(bands.get(outcome.outcomeId()), 2)
                                            : null))
                            .toList()));
        }
        return events;
    }
}
