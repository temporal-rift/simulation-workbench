package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.policy.domain.observation.Band;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.window.WindowKind;

/**
 * What {@code faction-greedy-v2} reads from one observation: a threshold-free rank of every visible outcome, the
 * seat's own commitments of the era, other participants' declared outcomes, and who the previous round's public
 * summary shows playing a Probability Shifter card.
 */
final class BoardReading {

    private final Map<UUID, Integer> rank = new HashMap<>();
    private final Map<UUID, UUID> eventOf = new HashMap<>();
    private final Set<UUID> low = new HashSet<>();
    private final Set<UUID> high = new HashSet<>();
    private final Set<UUID> commitment = new HashSet<>();
    private final Set<UUID> written = new HashSet<>();
    private final Set<UUID> writtenEvents = new HashSet<>();
    private final Set<UUID> fulfilledEvents = new HashSet<>();
    private final Set<UUID> othersDeclared = new HashSet<>();
    private final Set<UUID> previousRoundShifters = new HashSet<>();
    private final Integer round;
    private final int confirmedLinks;
    private UUID rewriteOutcome;
    private UUID threadOutcome;
    private boolean threaded;
    private boolean tapestryArmed;

    private BoardReading(EntitledObservation observation) {
        round = observation.window() instanceof DecisionWindow.ActionRound window ? window.round() : null;
        var board = observation.board();
        confirmedLinks = board.chain() == null ? 0 : board.chain().confirmedLinks();
        rankOutcomes(observation.events());
        readOwnSubmissions(board.ownSubmissions());
        if (observation.faction() == Faction.PROPHETS) {
            commitment.addAll(written);
        }
        if (observation.faction() == Faction.REVISIONISTS && rewriteOutcome != null) {
            commitment.add(rewriteOutcome);
        }
        if (observation.faction() == Faction.WEAVERS && threadOutcome != null) {
            commitment.add(threadOutcome);
        }
        var others = Set.copyOf(observation.otherPlayerIds());
        // The public declarations of the era are the only place the seat's own declared outcome appears: its own
        // declaration submission records the window, not the mode or target.
        for (var declaration : board.declarations()) {
            if (others.contains(declaration.playerId())) {
                othersDeclared.add(declaration.outcomeId());
            } else {
                commitment.add(declaration.outcomeId());
            }
        }
        var summary = board.lastRoundSummary();
        if (round != null && round >= 2 && summary != null && summary.round() == round - 1) {
            summary.actions().stream()
                    .filter(Board.ActionSummary::showsShifterCard)
                    .map(Board.ActionSummary::playerId)
                    .forEach(previousRoundShifters::add);
        }
    }

    static BoardReading of(EntitledObservation observation) {
        return new BoardReading(observation);
    }

    /**
     * Orders outcomes by public band (no band counts as {@code MEDIUM}), then by own Scan value else printed weight;
     * an outcome's rank is the share of the other outcomes strictly below it, scaled to 0..100.
     */
    private void rankOutcomes(List<EventView> events) {
        var keys = new HashMap<UUID, Integer>();
        for (var event : events) {
            for (var outcome : event.outcomes()) {
                eventOf.put(outcome.outcomeId(), event.eventId());
                keys.put(outcome.outcomeId(), key(outcome));
                var band = outcome.band() == null ? null : outcome.band().band();
                if (band == Band.LOW) {
                    low.add(outcome.outcomeId());
                }
                if (band == Band.HIGH) {
                    high.add(outcome.outcomeId());
                }
            }
        }
        var others = Math.max(1, keys.size() - 1);
        var sorted = keys.values().stream().sorted(Comparator.naturalOrder()).toList();
        for (var entry : keys.entrySet()) {
            var below =
                    (int) sorted.stream().filter(key -> key < entry.getValue()).count();
            rank.put(entry.getKey(), 100 * below / others);
        }
    }

    private static int key(OutcomeView outcome) {
        var band = outcome.band() == null ? Band.MEDIUM : outcome.band().band();
        return band.ordinal() * 1000 + outcome.knownWeight();
    }

    private void readOwnSubmissions(List<Board.OwnSubmission> submissions) {
        var actions = submissions.stream()
                .filter(own -> own.window() == WindowKind.ACTION_ROUND || own.window() == WindowKind.DECLARATION)
                .sorted(Comparator.comparing(own -> own.round() == null ? 0 : own.round()))
                .toList();
        for (var own : actions) {
            var special = own.special();
            if (special == null || own.outcomeId() == null) {
                continue;
            }
            switch (special) {
                case FORESIGHT -> write(own);
                case FULFILLMENT -> fulfilledEvents.add(own.eventId());
                case REWRITE -> rewriteOutcome = Objects.requireNonNullElse(rewriteOutcome, own.outcomeId());
                case THREAD -> {
                    threaded = true;
                    threadOutcome = own.outcomeId();
                }
                case REWEAVE -> threadOutcome = own.outcomeId();
                case TAPESTRY -> tapestryArmed = true;
                default -> {
                    // No other special makes a commitment.
                }
            }
        }
    }

    /** The first Foresight on an event writes its outcome; a later one on the same event writes nothing new. */
    private void write(Board.OwnSubmission foresight) {
        if (writtenEvents.add(foresight.eventId())) {
            written.add(foresight.outcomeId());
        }
    }

    int rank(UUID outcomeId) {
        return rank.getOrDefault(outcomeId, 0);
    }

    UUID eventOf(UUID outcomeId) {
        return eventOf.get(outcomeId);
    }

    boolean isLow(UUID outcomeId) {
        return low.contains(outcomeId);
    }

    boolean isHigh(UUID outcomeId) {
        return high.contains(outcomeId);
    }

    boolean isCommitment(UUID outcomeId) {
        return commitment.contains(outcomeId);
    }

    boolean isDeclaredByOther(UUID outcomeId) {
        return othersDeclared.contains(outcomeId);
    }

    boolean isWritten(UUID outcomeId) {
        return written.contains(outcomeId);
    }

    /** Whether the seat's accepted Foresight of this era already wrote an outcome (one writing per era). */
    boolean hasWrittenThisEra() {
        return !writtenEvents.isEmpty();
    }

    boolean isWrittenEvent(UUID eventId) {
        return writtenEvents.contains(eventId);
    }

    boolean isFulfilledEvent(UUID eventId) {
        return fulfilledEvents.contains(eventId);
    }

    boolean playedShifterLastRound(UUID playerId) {
        return previousRoundShifters.contains(playerId);
    }

    /** The open action round, or null outside action rounds. */
    Integer round() {
        return round;
    }

    int confirmedLinks() {
        return confirmedLinks;
    }

    UUID rewriteOutcome() {
        return rewriteOutcome;
    }

    UUID threadOutcome() {
        return threadOutcome;
    }

    boolean threaded() {
        return threaded;
    }

    boolean tapestryArmed() {
        return tapestryArmed;
    }
}
