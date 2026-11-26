package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * How {@code faction-spread} spreads its bets. For each bet action it draws the one event the action may keep, by a
 * seeded draw over the era's live events weighted by each event favorite's known weight, and excludes the action's
 * other candidates, so the recipe that follows can only aim the action at the favorite of the drawn event. The
 * Prophet's Foresight may name the event's runner-up instead, and the Eraser's Annihilate is drawn over every event.
 */
final class SpreadLayer {

    /** The bet actions, in the order their draws are taken. */
    private static final List<SpecialAction> BETS =
            List.of(SpecialAction.THREAD, SpecialAction.REWRITE, SpecialAction.FORESIGHT, SpecialAction.REWEAVE);

    private SpreadLayer() {}

    /**
     * Every candidate of a bet action that the draws do not keep. They are set aside, not excluded: an excluded special
     * reads to the recipe as refused on its whole event, which would drop the candidate the draw kept.
     */
    static Set<Candidate> setAside(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        var reading = BoardReading.of(observation);
        var rules = observation.tableRules();
        var refused = FactionRecipePolicy.refusedSpecials(excluded);
        var open = CandidateGenerator.generate(observation).stream()
                .filter(candidate -> !excluded.contains(candidate))
                .filter(candidate -> !refused.contains(FactionRecipePolicy.refusalKey(candidate)))
                .filter(candidate -> !ParadoxAwarePolicy.wasted(candidate, reading, rules))
                .toList();
        var linked = confirmedLinkEvents(observation);
        var result = new HashSet<Candidate>();
        for (var action : BETS) {
            var candidates = specials(open, action);
            var skipLinked = action == SpecialAction.THREAD || action == SpecialAction.REWEAVE;
            var kept = draw(
                    observation,
                    candidates,
                    event -> !event.stalled() && !(skipLinked && linked.contains(event.eventId())),
                    action == SpecialAction.FORESIGHT,
                    entropy);
            keep(candidates, kept, result);
        }
        var declarations = open.stream()
                .filter(Candidate.Declare.class::isInstance)
                .map(Candidate.Declare.class::cast)
                .toList();
        var declared = drawDeclaration(observation, declarations, entropy);
        declarations.stream()
                .filter(declaration -> declared.isEmpty() || !sameTarget(declaration, declared.get()))
                .forEach(result::add);
        var annihilates = specials(open, SpecialAction.ANNIHILATE);
        keep(annihilates, draw(observation, annihilates, event -> true, false, entropy), result);
        return result;
    }

    private static List<Candidate.PlaySpecial> specials(List<Candidate> open, SpecialAction action) {
        return open.stream()
                .filter(Candidate.PlaySpecial.class::isInstance)
                .map(Candidate.PlaySpecial.class::cast)
                .filter(special -> special.action() == action && special.target() instanceof Target.EventOutcome)
                .toList();
    }

    private static Set<UUID> confirmedLinkEvents(EntitledObservation observation) {
        var chain = observation.board().chain();
        return chain == null ? Set.of() : Set.copyOf(chain.confirmedEventIds());
    }

    /** Excludes every candidate of the action but those on the drawn outcome. */
    private static void keep(List<Candidate.PlaySpecial> candidates, Optional<Drawn> kept, Set<Candidate> setAside) {
        for (var candidate : candidates) {
            var target = (Target.EventOutcome) candidate.target();
            var onKept = kept.isPresent()
                    && target.eventId().equals(kept.get().eventId())
                    && target.outcomeId().equals(kept.get().outcomeId());
            if (!onKept) {
                setAside.add(candidate);
            }
        }
    }

    /** The outcome the draw keeps: the event drawn and the outcome it names. */
    private record Drawn(UUID eventId, UUID outcomeId) {}

    private static Optional<Drawn> draw(
            EntitledObservation observation,
            List<Candidate.PlaySpecial> candidates,
            Predicate<EventView> live,
            boolean mayNameRunnerUp,
            PolicyEntropy entropy) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var open = new HashSet<UUID>();
        for (var candidate : candidates) {
            var target = (Target.EventOutcome) candidate.target();
            open.add(target.outcomeId());
        }
        var pool = pool(observation, live, open, entropy);
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        var chosen = weighted(pool, entropy);
        var outcome = chosen.favorite();
        if (mayNameRunnerUp && entropy.nextIndex(2) == 1) {
            var runnerUp = favorite(
                    chosen.event().outcomes().stream()
                            .filter(candidate -> !candidate
                                    .outcomeId()
                                    .equals(chosen.favorite().outcomeId()))
                            .filter(candidate -> open.contains(candidate.outcomeId()))
                            .toList(),
                    entropy);
            if (runnerUp.isPresent()) {
                outcome = runnerUp.get();
            }
        }
        return Optional.of(new Drawn(chosen.event().eventId(), outcome.outcomeId()));
    }

    private static Optional<Drawn> drawDeclaration(
            EntitledObservation observation, List<Candidate.Declare> declarations, PolicyEntropy entropy) {
        if (declarations.isEmpty()) {
            return Optional.empty();
        }
        var open = new HashSet<UUID>();
        declarations.forEach(declaration -> open.add(declaration.outcomeId()));
        var pool = pool(observation, event -> !event.stalled(), open, entropy);
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        var chosen = weighted(pool, entropy);
        return Optional.of(new Drawn(chosen.event().eventId(), chosen.favorite().outcomeId()));
    }

    private static boolean sameTarget(Candidate.Declare declaration, Drawn drawn) {
        return declaration.eventId().equals(drawn.eventId())
                && declaration.outcomeId().equals(drawn.outcomeId());
    }

    /** An event of the pool with the outcome that is its favorite. */
    private record Entry(EventView event, OutcomeView favorite) {

        int weight() {
            return favorite.knownWeight();
        }
    }

    /** The live events whose favorite the action can still name, in the observation's event order. */
    private static List<Entry> pool(
            EntitledObservation observation, Predicate<EventView> live, Set<UUID> open, PolicyEntropy entropy) {
        var pool = new ArrayList<Entry>();
        for (var event : observation.events()) {
            if (!live.test(event)) {
                continue;
            }
            var favorite = favorite(event.outcomes(), entropy);
            if (favorite.isPresent() && open.contains(favorite.get().outcomeId())) {
                pool.add(new Entry(event, favorite.get()));
            }
        }
        return pool;
    }

    /** The event drawn with a chance proportional to its favorite's known weight; uniform when all weigh 0. */
    private static Entry weighted(List<Entry> pool, PolicyEntropy entropy) {
        var total = pool.stream().mapToInt(Entry::weight).sum();
        if (total == 0) {
            return pool.get(entropy.nextIndex(pool.size()));
        }
        var ticket = entropy.nextIndex(total);
        for (var entry : pool) {
            ticket -= entry.weight();
            if (ticket < 0) {
                return entry;
            }
        }
        throw new IllegalStateException("The ticket is below the total weight");
    }

    /**
     * The favorite of a set of outcomes: the highest public band, then the highest known weight; a tie is drawn. A draw
     * is taken only when there is a tie, so the entropy stream moves only then.
     */
    static Optional<OutcomeView> favorite(List<OutcomeView> outcomes, PolicyEntropy entropy) {
        if (outcomes.isEmpty()) {
            return Optional.empty();
        }
        var top = outcomes.stream()
                .map(BoardReading::key)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        var tied = outcomes.stream()
                .filter(outcome -> BoardReading.key(outcome) == top)
                .toList();
        return Optional.of(tied.size() == 1 ? tied.get(0) : tied.get(entropy.nextIndex(tied.size())));
    }
}
