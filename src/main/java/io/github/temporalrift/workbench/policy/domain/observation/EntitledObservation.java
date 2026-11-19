package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * The single frozen input a policy decides from. It can only hold facts the seat is entitled to:
 * its own faction, the visible events, the other participants' public identifiers, the public and own
 * era state on the board, the open window and the table's announced rules. Observer evidence, opposing
 * credentials or hands, scores, and execution-control state have no field here by construction.
 *
 * <p>{@code rules} is null for an observation recorded before table rules existed; read it through
 * {@link #tableRules()}.
 */
public record EntitledObservation(
        int seatIndex,
        Faction faction,
        List<EventView> events,
        List<UUID> otherPlayerIds,
        DecisionWindow window,
        Board board,
        TableRules rules) {

    public EntitledObservation {
        if (seatIndex < 0) {
            throw new IllegalArgumentException("seatIndex must not be negative");
        }
        Objects.requireNonNull(faction, "faction");
        Objects.requireNonNull(window, "window");
        events = List.copyOf(events);
        otherPlayerIds = List.copyOf(otherPlayerIds);
        board = board == null ? Board.EMPTY : board;
    }

    /** An observation without table rules, as recorded before they existed. */
    public EntitledObservation(
            int seatIndex,
            Faction faction,
            List<EventView> events,
            List<UUID> otherPlayerIds,
            DecisionWindow window,
            Board board) {
        this(seatIndex, faction, events, otherPlayerIds, window, board, null);
    }

    /** An observation without board facts. */
    public EntitledObservation(
            int seatIndex, Faction faction, List<EventView> events, List<UUID> otherPlayerIds, DecisionWindow window) {
        this(seatIndex, faction, events, otherPlayerIds, window, Board.EMPTY);
    }

    /** This observation under the given table rules. */
    public EntitledObservation withRules(TableRules tableRules) {
        return new EntitledObservation(seatIndex, faction, events, otherPlayerIds, window, board, tableRules);
    }

    /** The table's rules, or the served defaults when the observation predates them. */
    public TableRules tableRules() {
        return rules == null ? TableRules.DEFAULTS : rules;
    }
}
