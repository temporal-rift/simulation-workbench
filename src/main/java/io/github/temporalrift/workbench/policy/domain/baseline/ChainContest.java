package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * How {@code faction-greedy} contests a Weaver chain that is on its way to completion. The chain and the outcome its
 * latest Thread names are public facts, so any seat but the Weaver's can threaten the pending link: an Eraser by
 * annihilating it, anyone else by suppressing it with its strongest Suppress. A seat does not contest an outcome it
 * has itself committed to.
 */
final class ChainContest {

    /** Confirmed links from which a chain counts as a threat to the objective. */
    static final int THREATENING_LINKS = 2;

    private ChainContest() {}

    /** The contest play for this action round, if the seat should make one and has one that is still open. */
    static Optional<Candidate> choose(EntitledObservation observation, Set<Candidate> excluded) {
        if (!(observation.window() instanceof DecisionWindow.ActionRound round)
                || observation.faction() == Faction.WEAVERS) {
            return Optional.empty();
        }
        var chain = observation.board().chain();
        if (chain == null
                || chain.status() != Board.ChainStatus.ACTIVE
                || chain.confirmedLinks() < THREATENING_LINKS
                || chain.pendingLink() == null) {
            return Optional.empty();
        }
        var link = chain.pendingLink();
        if (BoardReading.of(observation).isCommitment(link.outcomeId())) {
            return Optional.empty();
        }
        var pending = new Target.EventOutcome(link.eventId(), link.outcomeId());
        var candidates = CandidateGenerator.generate(observation).stream()
                .filter(candidate -> !excluded.contains(candidate))
                .toList();
        var unavailable = round.specials().stream()
                .filter(PlayableSpecial::unavailable)
                .map(PlayableSpecial::action)
                .collect(java.util.stream.Collectors.toSet());
        var annihilate = candidates.stream()
                .filter(Candidate.PlaySpecial.class::isInstance)
                .map(Candidate.PlaySpecial.class::cast)
                .filter(special -> special.action() == SpecialAction.ANNIHILATE
                        && !unavailable.contains(special.action())
                        && pending.equals(special.target()))
                .findFirst();
        if (annihilate.isPresent()) {
            return Optional.of(annihilate.get());
        }
        var cards = FactionRecipePolicy.cardsById(observation);
        return candidates.stream()
                .filter(Candidate.PlayCard.class::isInstance)
                .map(Candidate.PlayCard.class::cast)
                .filter(play -> pending.equals(play.target()))
                .filter(play -> cards.get(play.cardInstanceId()) != null
                        && cards.get(play.cardInstanceId()).type() == CardType.SUPPRESS)
                .max(Comparator.comparingInt(
                        play -> cards.get(play.cardInstanceId()).grade().ordinal()))
                .map(Candidate.class::cast);
    }
}
