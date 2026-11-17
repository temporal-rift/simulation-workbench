package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * The single frozen input a policy decides from. It can only hold facts the seat is entitled to:
 * its own faction, the visible events, the other participants' public identifiers, the public and own
 * era state on the board, and the open window. Observer evidence, opposing credentials or hands, scores,
 * and execution-control state have no field here by construction.
 */
public record EntitledObservation(
        int seatIndex,
        Faction faction,
        List<EventView> events,
        List<UUID> otherPlayerIds,
        DecisionWindow window,
        Board board) {

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

    /** An observation without board facts. */
    public EntitledObservation(
            int seatIndex, Faction faction, List<EventView> events, List<UUID> otherPlayerIds, DecisionWindow window) {
        this(seatIndex, faction, events, otherPlayerIds, window, Board.EMPTY);
    }
}
