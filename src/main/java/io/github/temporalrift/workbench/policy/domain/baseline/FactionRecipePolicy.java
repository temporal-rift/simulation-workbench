package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.policy.domain.baseline.FactionPreferences.Bias;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableCard;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * The base recipe of {@code faction-greedy}: one recipe for every faction. It reads the board (public bands, own Scan,
 * printed weights), drops actions it knows are wasted, prefers its own commitment of the era, does not feed another
 * participant's declared outcome, and plays a faction's counter when the counter's trigger is present. It never
 * models resolution, scoring or another participant's faction.
 */
final class FactionRecipePolicy implements BotPolicy {

    static final int AFFINITY_SCALE = 1000;
    static final int DECLARATION_SCORE = 8 * AFFINITY_SCALE;
    static final int MOMENTUM_BONUS = AFFINITY_SCALE / 20;
    static final int COMMITMENT_BONUS = 200;
    static final int DECLARED_BY_OTHER_PENALTY = 200;
    static final int PROTECT_AFFINITY = 7;
    static final int GUARD_AFFINITY = 6;
    static final int COUNTER_AFFINITY = 5;

    static final String DEFINITION = """
            faction-greedy base recipe: score = affinity*1000 + board rank (0..100) + commitment adjustment
            board rank: outcomes ordered by public band (none = MEDIUM), then own Scan value else printed weight; \
            rank = 100 * outcomes strictly below / (outcomes - 1); TRAILING = 100 - rank; pairs average both ends
            commitment: Activist declared outcome, Revisionist first Rewrite outcome, Weaver latest Thread/Reweave \
            outcome, Prophet first Foresight outcome per event
            adjustment for Push, Swing destination and Mimic: +200 toward own commitment not LOW; -200 toward another \
            participant's declared outcome that is not own commitment
            refused special: once the service refuses a special in a window, it is not tried again there on the same \
            event or participant
            waste (never chosen): Foresight after the seat already wrote an outcome this era (one writing per era); \
            Thread after an accepted Thread, unless a Tapestry was armed and the Thread outcome fell to LOW; \
            Rewrite after an accepted Rewrite; Obscure in round 3; Reweave without a Thread, after a Tapestry, \
            while the Thread outcome is not LOW, or onto the Thread outcome; Tapestry without a Thread, \
            below 2 confirmed links, already armed, or off the Thread outcome; Fulfillment off a written outcome or \
            on an event already fulfilled; Seal off a written outcome; Mimic off the Rewrite outcome; Expose outside \
            round 2 or against a participant whose previous-round summary shows no Probability Shifter card; Corrupt \
            against such a participant; a Push, Swing, Thread, Rewrite, Foresight, Mimic, Reweave, Seal, Fulfillment \
            or Tapestry toward a LOW outcome when the same card or special has a non-LOW alternative
            triggered affinity: Tapestry 7; Reweave 7 (only reached when the Thread outcome is LOW); Fulfillment 7 and \
            Seal 6 on a HIGH written outcome; Expose 5; Mimic 5 when not LOW; Corrupt 5
            declaration: 8000 (+50 Momentum) + board rank; hand selection: sum of card affinities * 1000
            base affinities and leans:
            """ + FactionPreferences.canonicalDefinition();

    @Override
    public PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        return decide(observation, excluded, entropy, FactionRecipePolicy::wasted);
    }

    /**
     * The base recipe with a given waste rule, so a later bundle can reuse the recipe while correcting what it knows to
     * be wasted. the base policy always passes {@link #wasted}.
     */
    PolicyDecision decide(
            EntitledObservation observation,
            Set<Candidate> excluded,
            PolicyEntropy entropy,
            BiPredicate<Candidate, BoardReading> waste) {
        var available = CandidateGenerator.generate(observation).stream()
                .filter(candidate -> !excluded.contains(candidate))
                .toList();
        if (available.isEmpty()) {
            return new PolicyDecision.Exhausted();
        }
        var reading = BoardReading.of(observation);
        var cards = cardsById(observation);
        var refused = refusedSpecials(excluded);
        var survivors = withoutLowWhereAvoidable(
                available.stream()
                        .filter(candidate -> !waste.test(candidate, reading))
                        .filter(candidate -> !refused.contains(refusalKey(candidate)))
                        .toList(),
                cards,
                reading);
        var best = Long.MIN_VALUE;
        var top = new ArrayList<Candidate>();
        for (var candidate : survivors) {
            var score = score(candidate, observation, cards, reading);
            if (score > best) {
                best = score;
                top.clear();
            }
            if (score == best) {
                top.add(candidate);
            }
        }
        if (top.isEmpty()) {
            return new PolicyDecision.Exhausted();
        }
        return new PolicyDecision.Chosen(top.get(entropy.nextIndex(top.size())));
    }

    /** Whether the candidate is an action the seat can tell has no effect, or is not yet called for. */
    static boolean wasted(Candidate candidate, BoardReading reading) {
        if (!(candidate instanceof Candidate.PlaySpecial(var action, var target))) {
            return false;
        }
        return switch (action) {
            case FORESIGHT -> reading.hasWrittenThisEra() || reading.isWrittenEvent(eventOf(target));
            case THREAD -> reading.threaded() && !protectedLinkLost(reading);
            case REWRITE -> reading.rewriteOutcome() != null;
            case OBSCURE -> Integer.valueOf(3).equals(reading.round());
            case REWEAVE ->
                !reading.threaded()
                        || reading.tapestryArmed()
                        || !reading.isLow(reading.threadOutcome())
                        || outcomeOf(target).equals(reading.threadOutcome());
            case TAPESTRY ->
                !reading.threaded()
                        || reading.confirmedLinks() < 2
                        || reading.tapestryArmed()
                        || !outcomeOf(target).equals(reading.threadOutcome());
            case FULFILLMENT -> !reading.isWritten(outcomeOf(target)) || reading.isFulfilledEvent(eventOf(target));
            case SEAL -> !reading.isWritten(outcomeOf(target));
            case MIMIC -> !outcomeOf(target).equals(reading.rewriteOutcome());
            case EXPOSE ->
                !Integer.valueOf(2).equals(reading.round()) || !reading.playedShifterLastRound(playerOf(target));
            case CORRUPT -> !reading.playedShifterLastRound(playerOf(target));
            default -> false;
        };
    }

    /**
     * A Tapestry absorbs an Annihilate on the pending link and clears the link, so a protected Thread outcome that fell
     * to LOW leaves the Weaver free to Thread again this era, and a Reweave has no link to move.
     */
    private static boolean protectedLinkLost(BoardReading reading) {
        return reading.tapestryArmed() && reading.isLow(reading.threadOutcome());
    }

    /**
     * The specials the service refused in this window, each with the event or participant it named. A refusal is
     * about the special's timing, budget or that event (a stalled one, say), so another outcome of the same event or
     * another target of the same participant is not tried, while another event still is.
     */
    private static Set<Object> refusedSpecials(Set<Candidate> excluded) {
        var refused = new HashSet<Object>();
        for (var candidate : excluded) {
            var key = refusalKey(candidate);
            if (key != null) {
                refused.add(key);
            }
        }
        return refused;
    }

    /** The special and the event or participant it names, or null for anything but a special. */
    private static Object refusalKey(Candidate candidate) {
        if (!(candidate instanceof Candidate.PlaySpecial(var action, var target))) {
            return null;
        }
        return switch (target) {
            case Target.EventOutcome(var eventId, var _) -> List.of(action, eventId);
            case Target.Player(var playerId) -> List.of(action, playerId);
            default -> List.of(action);
        };
    }

    /**
     * Drops a choice toward a LOW outcome when the same card or special has a choice toward a non-LOW one, so a
     * forced choice survives.
     */
    private static List<Candidate> withoutLowWhereAvoidable(
            List<Candidate> candidates, Map<UUID, DealtCard> cards, BoardReading reading) {
        var withAlternative = new HashSet<Object>();
        for (var candidate : candidates) {
            var destination = destination(candidate, cards);
            if (destination != null && !reading.isLow(destination)) {
                withAlternative.add(actionKey(candidate));
            }
        }
        return candidates.stream()
                .filter(candidate -> {
                    var destination = destination(candidate, cards);
                    return destination == null
                            || !reading.isLow(destination)
                            || !withAlternative.contains(actionKey(candidate));
                })
                .toList();
    }

    private static long score(
            Candidate candidate, EntitledObservation observation, Map<UUID, DealtCard> cards, BoardReading reading) {
        var faction = observation.faction();
        return switch (candidate) {
            case Candidate.KeepHand(var cardInstanceIds) ->
                cardInstanceIds.stream()
                        .mapToLong(id -> (long) FactionPreferences.card(
                                                faction, cards.get(id).type())
                                        .affinity()
                                * AFFINITY_SCALE)
                        .sum();
            case Candidate.Declare(var mode, var eventId, var outcomeId) ->
                DECLARATION_SCORE + (mode == DeclarationMode.MOMENTUM ? MOMENTUM_BONUS : 0) + reading.rank(outcomeId);
            case Candidate.PlayCard(var cardInstanceId, var target) -> {
                var preference = FactionPreferences.card(
                        faction, cards.get(cardInstanceId).type());
                yield preference.affinity() * (long) AFFINITY_SCALE
                        + lean(preference.bias(), target, reading)
                        + adjustment(destination(candidate, cards), reading);
            }
            case Candidate.PlayParadoxCard(var cardInstanceId, var target) -> {
                var preference = FactionPreferences.card(
                        faction, cards.get(cardInstanceId).type());
                yield preference.affinity() * (long) AFFINITY_SCALE + lean(preference.bias(), target, reading);
            }
            case Candidate.PlaySpecial(var action, var target) -> {
                var preference = FactionPreferences.special(faction, action);
                yield affinity(action, target, preference.affinity(), reading) * (long) AFFINITY_SCALE
                        + lean(preference.bias(), target, reading)
                        + (action == SpecialAction.MIMIC ? adjustment(outcomeOf(target), reading) : 0);
            }
            case Candidate.Decline _, Candidate.Pass _, Candidate.PassParadox _, Candidate.ConfirmReady _ -> 0;
        };
    }

    /** The counter's affinity when its trigger is present, else the faction's base affinity. */
    private static int affinity(SpecialAction action, Target target, int base, BoardReading reading) {
        return switch (action) {
            case TAPESTRY -> PROTECT_AFFINITY;
            case REWEAVE -> PROTECT_AFFINITY;
            case FULFILLMENT -> reading.isHigh(outcomeOf(target)) ? PROTECT_AFFINITY : base;
            case SEAL -> reading.isHigh(outcomeOf(target)) ? GUARD_AFFINITY : base;
            case EXPOSE, CORRUPT -> COUNTER_AFFINITY;
            case MIMIC -> reading.isLow(outcomeOf(target)) ? base : COUNTER_AFFINITY;
            default -> base;
        };
    }

    private static int adjustment(UUID destination, BoardReading reading) {
        if (destination == null) {
            return 0;
        }
        if (reading.isCommitment(destination)) {
            return reading.isLow(destination) ? 0 : COMMITMENT_BONUS;
        }
        return reading.isDeclaredByOther(destination) ? -DECLARED_BY_OTHER_PENALTY : 0;
    }

    private static int lean(Bias bias, Target target, BoardReading reading) {
        if (bias == Bias.NEUTRAL) {
            return 0;
        }
        return switch (target) {
            case Target.EventOutcome(var _, var outcomeId) -> lean(bias, reading.rank(outcomeId));
            case Target.OutcomePair(var _, var sourceOutcomeId, var targetOutcomeId) ->
                (lean(bias, reading.rank(targetOutcomeId)) + lean(flip(bias), reading.rank(sourceOutcomeId))) / 2;
            default -> 0;
        };
    }

    private static int lean(Bias bias, int rank) {
        return bias == Bias.LEADING ? rank : 100 - rank;
    }

    private static Bias flip(Bias bias) {
        return bias == Bias.LEADING ? Bias.TRAILING : Bias.LEADING;
    }

    /** The outcome a supporting action moves toward, or null when the action supports no outcome. */
    private static UUID destination(Candidate candidate, Map<UUID, DealtCard> cards) {
        return switch (candidate) {
            case Candidate.PlayCard(var cardInstanceId, Target.EventOutcome(var _, var outcomeId))
            when cards.get(cardInstanceId).type() == CardType.PUSH -> outcomeId;
            case Candidate.PlayCard(var cardInstanceId, Target.OutcomePair(var _, var _, var outcomeId))
            when cards.get(cardInstanceId).type() == CardType.SWING -> outcomeId;
            case Candidate.PlaySpecial(var action, Target.EventOutcome(var _, var outcomeId))
            when SUPPORTING.contains(action) -> outcomeId;
            default -> null;
        };
    }

    private static final Set<SpecialAction> SUPPORTING = Set.of(
            SpecialAction.THREAD,
            SpecialAction.REWRITE,
            SpecialAction.FORESIGHT,
            SpecialAction.MIMIC,
            SpecialAction.REWEAVE,
            SpecialAction.SEAL,
            SpecialAction.FULFILLMENT,
            SpecialAction.TAPESTRY);

    /** The card instance or special a candidate plays, so its choices can be compared. */
    private static Object actionKey(Candidate candidate) {
        return switch (candidate) {
            case Candidate.PlayCard(var cardInstanceId, var _) -> cardInstanceId;
            case Candidate.PlaySpecial(var action, var _) -> action;
            default -> candidate;
        };
    }

    private static UUID outcomeOf(Target target) {
        return target instanceof Target.EventOutcome(var _, var outcomeId) ? outcomeId : null;
    }

    private static UUID eventOf(Target target) {
        return target instanceof Target.EventOutcome(var eventId, var _) ? eventId : null;
    }

    private static UUID playerOf(Target target) {
        return target instanceof Target.Player(var playerId) ? playerId : null;
    }

    static Map<UUID, DealtCard> cardsById(EntitledObservation observation) {
        List<DealtCard> cards = switch (observation.window()) {
            case DecisionWindow.HandSelection window -> window.deal();
            case DecisionWindow.ActionRound window ->
                window.cards().stream().map(PlayableCard::card).toList();
            case DecisionWindow.ParadoxResolution window -> window.offer();
            default -> List.of();
        };
        return cards.stream().collect(Collectors.toMap(DealtCard::cardInstanceId, Function.identity()));
    }
}
