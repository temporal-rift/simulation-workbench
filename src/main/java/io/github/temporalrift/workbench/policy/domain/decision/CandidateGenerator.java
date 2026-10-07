package io.github.temporalrift.workbench.policy.domain.decision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;

/**
 * Enumerates every candidate of an observation's window in one canonical total order (rank, then
 * the candidate's record text), so equal observations always list candidates identically.
 */
public final class CandidateGenerator {

    private static final Comparator<Candidate> CANONICAL =
            Comparator.comparingInt(Candidate::rank).thenComparing(Candidate::toString);

    private CandidateGenerator() {}

    public static List<Candidate> generate(EntitledObservation observation) {
        var candidates = new LinkedHashSet<Candidate>();
        switch (observation.window()) {
            case DecisionWindow.HandSelection window -> handSelection(window, candidates);
            case DecisionWindow.Declaration window -> declaration(window, observation, candidates);
            case DecisionWindow.ActionRound window -> actionRound(window, observation, candidates);
            case DecisionWindow.ParadoxResolution window -> paradox(window, observation, candidates);
            case DecisionWindow.TerminalReadiness _ -> candidates.add(new Candidate.ConfirmReady());
        }
        return candidates.stream().sorted(CANONICAL).toList();
    }

    private static void handSelection(DecisionWindow.HandSelection window, LinkedHashSet<Candidate> out) {
        var ids = window.deal().stream()
                .map(DealtCard::cardInstanceId)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        if (ids.size() < window.keepCount()) {
            return;
        }
        combinations(ids, window.keepCount()).forEach(keep -> out.add(new Candidate.KeepHand(keep)));
    }

    private static void declaration(
            DecisionWindow.Declaration window, EntitledObservation observation, LinkedHashSet<Candidate> out) {
        out.add(new Candidate.Decline());
        for (var mode : window.eligibleModes()) {
            for (var event : observation.events()) {
                for (var outcome : event.outcomes()) {
                    out.add(new Candidate.Declare(mode, event.eventId(), outcome.outcomeId()));
                }
            }
        }
    }

    private static void actionRound(
            DecisionWindow.ActionRound window, EntitledObservation observation, LinkedHashSet<Candidate> out) {
        out.add(new Candidate.Pass());
        for (var playable : window.cards()) {
            for (var target : targets(playable.shape(), playable.targetCount(), observation)) {
                out.add(new Candidate.PlayCard(playable.card().cardInstanceId(), target));
            }
        }
        for (var special : window.specials()) {
            for (var target : targets(special.shape(), 1, observation)) {
                out.add(new Candidate.PlaySpecial(special.action(), target));
            }
        }
    }

    private static void paradox(
            DecisionWindow.ParadoxResolution window, EntitledObservation observation, LinkedHashSet<Candidate> out) {
        out.add(new Candidate.PassParadox());
        for (var card : window.offer()) {
            for (var event : observation.events()) {
                for (var outcome : event.outcomes()) {
                    out.add(new Candidate.PlayParadoxCard(
                            card.cardInstanceId(), new Target.EventOutcome(event.eventId(), outcome.outcomeId())));
                }
            }
        }
    }

    private static List<Target> targets(TargetShape shape, int count, EntitledObservation observation) {
        var events = observation.events().stream()
                .sorted(Comparator.comparing(
                        (EventView event) -> event.eventId().toString()))
                .toList();
        var players = observation.otherPlayerIds().stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        return switch (shape) {
            case DISGUISE ->
                Arrays.stream(CardCategory.values())
                        .<Target>map(Target.Disguise::new)
                        .toList();
            case EVENT_OUTCOME -> eventOutcomes(events);
            case OUTCOME_PAIR -> outcomePairs(events);
            case EVENT_LIST ->
                subsets(events.stream().map(EventView::eventId).toList(), count).stream()
                        .<Target>map(Target.Events::new)
                        .toList();
            case PLAYER -> players.stream().<Target>map(Target.Player::new).toList();
            case PLAYER_LIST ->
                subsets(players, count).stream()
                        .<Target>map(Target.Players::new)
                        .toList();
        };
    }

    private static List<Target> eventOutcomes(List<EventView> events) {
        var targets = new ArrayList<Target>();
        for (var event : events) {
            for (var outcome : event.outcomes()) {
                targets.add(new Target.EventOutcome(event.eventId(), outcome.outcomeId()));
            }
        }
        return targets;
    }

    private static List<Target> outcomePairs(List<EventView> events) {
        var targets = new ArrayList<Target>();
        for (var event : events) {
            for (OutcomeView source : event.outcomes()) {
                for (OutcomeView destination : event.outcomes()) {
                    if (!source.outcomeId().equals(destination.outcomeId())) {
                        targets.add(
                                new Target.OutcomePair(event.eventId(), source.outcomeId(), destination.outcomeId()));
                    }
                }
            }
        }
        return targets;
    }

    /** Every subset of the list with {@code count} members (or all of them if fewer); none for an empty list. */
    private static <T> List<List<T>> subsets(List<T> items, int count) {
        return items.isEmpty() ? List.of() : combinations(items, Math.min(count, items.size()));
    }

    /** All {@code size}-element subsets of the list, preserving element order, in lexicographic order. */
    static <T> List<List<T>> combinations(List<T> items, int size) {
        var result = new ArrayList<List<T>>();
        collect(items, size, 0, new ArrayList<>(), result);
        return result;
    }

    private static <T> void collect(List<T> items, int size, int from, List<T> current, List<List<T>> out) {
        if (current.size() == size) {
            out.add(List.copyOf(current));
            return;
        }
        for (var i = from; i <= items.size() - (size - current.size()); i++) {
            current.add(items.get(i));
            collect(items, size, i + 1, current, out);
            current.removeLast();
        }
    }
}
