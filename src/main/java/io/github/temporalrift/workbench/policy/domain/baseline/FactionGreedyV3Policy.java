package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.TableRules;
import io.github.temporalrift.workbench.shared.game.CardType;

/**
 * {@code faction-greedy-v3}: the v2 recipe with two instrument fixes. In paradox resolution it plays Stabilize or
 * Detonate only when the table's costs make clearing or cascading better for it, judged by its own commitment on the
 * affected event; otherwise it plays as v2 among the remaining cards, never Stabilize or Detonate by habit. Under the
 * current Tapestry rule it keeps a protected link pending after an Annihilate instead of Threading again, and may
 * Reweave it while Tapestry is armed.
 */
final class FactionGreedyV3Policy implements BotPolicy {

    /** What a commitment on an event is worth to the seat: about one win of its signature bet. */
    static final int COMMITMENT_VALUE = 3;

    static final String DEFINITION = """
            faction-greedy v3: faction-greedy v2 with two fixes
            paradox resolution, per affected event in canonical order: commitment = an outcome of the event is the \
            seat's own commitment (v2 sense); clear value = commitment ? 3 : 0, plus the table's Stabilize delta when \
            the seat stabilizes; cascade value = -(commitment ? 3 : 0), plus the table's Detonate delta when Detonate \
            contests Stabilize, else +|cascade penalty| (the others pay double); play STABILIZE on the first event \
            whose clear value beats both 0 and its cascade value, else DETONATE on the first event whose cascade \
            value beats 0, else the v2 choice with every STABILIZE and DETONATE excluded
            Tapestry, while it does not absorb the Annihilate: a Thread after an accepted Thread is always wasted; \
            Reweave is allowed while Tapestry is armed
            """ + FactionGreedyV2Policy.DEFINITION;

    private final FactionGreedyV2Policy recipe = new FactionGreedyV2Policy();

    @Override
    public PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        var rules = observation.tableRules();
        if (observation.window() instanceof DecisionWindow.ParadoxResolution) {
            var reading = BoardReading.of(observation);
            var available = CandidateGenerator.generate(observation).stream()
                    .filter(candidate -> !excluded.contains(candidate))
                    .toList();
            var cards = FactionGreedyV2Policy.cardsById(observation);
            var chosen = paradoxChoice(observation, available, cards, reading, rules);
            if (chosen.isPresent()) {
                return new PolicyDecision.Chosen(chosen.get());
            }
            var withoutParadoxCards = new HashSet<>(excluded);
            available.stream()
                    .filter(candidate -> isParadoxCard(candidate, cards))
                    .forEach(withoutParadoxCards::add);
            return recipe.decide(observation, withoutParadoxCards, entropy, FactionGreedyV2Policy::wasted);
        }
        return recipe.decide(observation, excluded, entropy, (candidate, reading) -> wasted(candidate, reading, rules));
    }

    private static Optional<Candidate> paradoxChoice(
            EntitledObservation observation,
            List<Candidate> available,
            Map<UUID, DealtCard> cards,
            BoardReading reading,
            TableRules rules) {
        for (var event : observation.events()) {
            var worth = Worth.of(event, reading, rules);
            if (worth.clear() > 0 && worth.clear() > worth.cascade()) {
                var stabilize = play(available, cards, event.eventId(), CardType.STABILIZE);
                if (stabilize.isPresent()) {
                    return stabilize;
                }
            }
            if (worth.cascade() > 0) {
                var detonate = play(available, cards, event.eventId(), CardType.DETONATE);
                if (detonate.isPresent()) {
                    return detonate;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * What clearing and what tearing an event is worth to the seat, in points: its stake in the event, if it backs the
     * outcome it committed to there, plus the announced costs of each result.
     */
    private record Worth(int clear, int cascade) {

        static Worth of(EventView event, BoardReading reading, TableRules rules) {
            var committed =
                    event.outcomes().stream().map(OutcomeView::outcomeId).anyMatch(reading::isCommitment);
            var stake = committed ? COMMITMENT_VALUE : 0;
            var tear = rules.detonateContestsStabilize() ? rules.detonateDelta() : Math.abs(rules.cascadePenalty());
            return new Worth(stake + rules.stabilizeDelta(), -stake + tear);
        }
    }

    /** The first available play of the given paradox card against the event, in canonical candidate order. */
    private static Optional<Candidate> play(
            List<Candidate> available, Map<UUID, DealtCard> cards, UUID eventId, CardType type) {
        return available.stream()
                .filter(candidate -> candidate instanceof Candidate.PlayParadoxCard(var cardId, var target)
                        && cards.get(cardId).type() == type
                        && target.eventId().equals(eventId))
                .findFirst();
    }

    private static boolean isParadoxCard(Candidate candidate, Map<UUID, DealtCard> cards) {
        if (!(candidate instanceof Candidate.PlayParadoxCard paradoxCard)) {
            return false;
        }
        var type = cards.get(paradoxCard.cardInstanceId()).type();
        return type == CardType.STABILIZE || type == CardType.DETONATE;
    }

    /** v2's waste rule, with the Thread and Reweave cases following the current Tapestry rule. */
    static boolean wasted(Candidate candidate, BoardReading reading, TableRules rules) {
        if (rules.tapestryAbsorbsChainConflict()
                || !(candidate instanceof Candidate.PlaySpecial(var action, var target))) {
            return FactionGreedyV2Policy.wasted(candidate, reading);
        }
        return switch (action) {
            // The protected link stays pending after an Annihilate, so the service refuses a second Thread.
            case THREAD -> reading.threaded();
            // Reweave is the escape the current rule gives a protected link, so an armed Tapestry does not block it.
            case REWEAVE ->
                !reading.threaded()
                        || !reading.isLow(reading.threadOutcome())
                        || outcomeOf(target).equals(reading.threadOutcome());
            default -> FactionGreedyV2Policy.wasted(candidate, reading);
        };
    }

    private static UUID outcomeOf(Target target) {
        return target instanceof Target.EventOutcome eventOutcome ? eventOutcome.outcomeId() : null;
    }
}
