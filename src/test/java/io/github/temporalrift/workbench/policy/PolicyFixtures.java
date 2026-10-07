package io.github.temporalrift.workbench.policy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.CardGrade;
import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableCard;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;

/** Scripted observations covering every faction, window and target shape. */
public final class PolicyFixtures {

    public static final UUID EVENT_A = id(0xA0);
    public static final UUID EVENT_B = id(0xB0);
    public static final UUID EVENT_C = id(0xC0);
    /** Outcomes of event A, weighted 20, 30 and 50. */
    public static final UUID A_LOW = id(0xA1);

    public static final UUID A_HIGH = id(0xA3);

    private static final Map<Faction, List<PlayableSpecial>> SPECIALS = Map.of(
            Faction.ERASERS,
            List.of(
                    new PlayableSpecial(SpecialAction.ANNIHILATE, TargetShape.EVENT_OUTCOME),
                    new PlayableSpecial(SpecialAction.CORRUPT, TargetShape.PLAYER),
                    new PlayableSpecial(SpecialAction.CASCADE, TargetShape.EVENT_OUTCOME)),
            Faction.PROPHETS,
            List.of(
                    new PlayableSpecial(SpecialAction.FORESIGHT, TargetShape.EVENT_OUTCOME),
                    new PlayableSpecial(SpecialAction.SEAL, TargetShape.EVENT_OUTCOME),
                    new PlayableSpecial(SpecialAction.FULFILLMENT, TargetShape.EVENT_OUTCOME)),
            Faction.REVISIONISTS,
            List.of(
                    new PlayableSpecial(SpecialAction.REWRITE, TargetShape.EVENT_OUTCOME),
                    new PlayableSpecial(SpecialAction.MIMIC, TargetShape.EVENT_OUTCOME),
                    new PlayableSpecial(SpecialAction.OBSCURE, TargetShape.EVENT_OUTCOME)),
            Faction.WEAVERS,
            List.of(
                    new PlayableSpecial(SpecialAction.THREAD, TargetShape.EVENT_OUTCOME),
                    new PlayableSpecial(SpecialAction.TAPESTRY, TargetShape.EVENT_OUTCOME),
                    new PlayableSpecial(SpecialAction.REWEAVE, TargetShape.EVENT_OUTCOME)),
            Faction.ACTIVISTS,
            List.of(new PlayableSpecial(SpecialAction.EXPOSE, TargetShape.PLAYER)));

    private PolicyFixtures() {}

    public static UUID id(int n) {
        return new UUID(0L, n);
    }

    public static List<EventView> events() {
        return List.of(event(EVENT_A, 0xA0), event(EVENT_B, 0xB0), event(EVENT_C, 0xC0));
    }

    private static EventView event(UUID eventId, int base) {
        return new EventView(
                eventId,
                List.of(
                        new OutcomeView(id(base + 1), 20, null),
                        new OutcomeView(id(base + 2), 30, null),
                        new OutcomeView(id(base + 3), 50, null)));
    }

    public static List<UUID> players() {
        return List.of(id(0x01), id(0x02), id(0x03));
    }

    public static DealtCard card(int n, CardType type) {
        return new DealtCard(id(0x1000 + n), type, CardGrade.II, CardCategory.PROBABILITY_SHIFTER);
    }

    public static EntitledObservation observation(Faction faction, DecisionWindow window) {
        return new EntitledObservation(0, faction, events(), players(), window);
    }

    public static EntitledObservation handSelection(Faction faction) {
        var deal = List.of(
                card(1, CardType.PUSH),
                card(2, CardType.SUPPRESS),
                card(3, CardType.SWING),
                card(4, CardType.AMPLIFY),
                card(5, CardType.SCAN),
                card(6, CardType.JAM),
                card(7, CardType.NULLIFY));
        return observation(faction, new DecisionWindow.HandSelection(1, deal, 5));
    }

    public static EntitledObservation declaration(Faction faction) {
        return observation(faction, new DecisionWindow.Declaration(1, List.of(DeclarationMode.RALLY)));
    }

    /** An action round whose hand covers every target shape. */
    public static EntitledObservation actionRound(Faction faction) {
        var cards = List.of(
                new PlayableCard(card(1, CardType.PUSH), TargetShape.EVENT_OUTCOME, 1),
                new PlayableCard(card(2, CardType.SWING), TargetShape.OUTCOME_PAIR, 1),
                new PlayableCard(card(3, CardType.SCAN), TargetShape.EVENT_LIST, 2),
                new PlayableCard(card(4, CardType.DECOY), TargetShape.DISGUISE, 1),
                new PlayableCard(card(5, CardType.AMPLIFY), TargetShape.PLAYER, 1),
                new PlayableCard(card(6, CardType.NULLIFY), TargetShape.PLAYER_LIST, 2));
        return observation(faction, new DecisionWindow.ActionRound(1, 2, cards, SPECIALS.get(faction)));
    }

    public static EntitledObservation paradox(Faction faction, boolean emptyOffer) {
        var offer =
                emptyOffer ? List.<DealtCard>of() : List.of(card(8, CardType.STABILIZE), card(9, CardType.DETONATE));
        return observation(faction, new DecisionWindow.ParadoxResolution(1, offer));
    }

    public static EntitledObservation terminal(Faction faction) {
        return observation(faction, new DecisionWindow.TerminalReadiness(5));
    }

    public static List<EntitledObservation> allWindows(Faction faction) {
        return List.of(
                handSelection(faction),
                declaration(faction),
                actionRound(faction),
                paradox(faction, false),
                paradox(faction, true),
                terminal(faction));
    }
}
