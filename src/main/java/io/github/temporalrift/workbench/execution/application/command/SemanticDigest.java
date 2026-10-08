package io.github.temporalrift.workbench.execution.application.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;

/**
 * The semantic identity of a case: its setup, every accepted decision and the authoritative ending.
 * Transport identifiers and timestamps are excluded, and the decisions come from the durable ledger, so
 * the digest does not change when a case is interrupted and recovered.
 */
final class SemanticDigest {

    private static final String ACTION_WINDOW_SUFFIX = "/action";

    private SemanticDigest() {}

    static String of(
            UUID caseKey,
            String seed,
            List<SeatPlan> seats,
            List<Slot> decisions,
            GameSession.AuthoritativeEnding ending) {
        var text = new ArrayList<String>();
        text.add("case|" + caseKey + "|" + seed);
        seats.forEach(seat -> text.add("seat|" + seat.seatIndex() + "|" + seat.faction() + "|" + seat.policyId() + "@"
                + seat.policyVersion()));
        decisions.forEach(slot ->
                text.add("decision|" + slot.id().windowKey() + "|" + slot.id().seatIndex() + "|" + slot.request()));
        text.add("end|" + ending.endReason() + "|" + ending.eras());
        ending.winners().forEach(w -> text.add("winner|" + w.seatIndex() + "|" + w.faction() + "|" + w.winType()));
        ending.finalScores().forEach(s -> text.add("score|" + s.seatIndex() + "|" + s.faction() + "|" + s.score()));
        try {
            var bytes = String.join("\n", text).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Distinct action rounds that took at least one accepted decision. */
    static int rounds(List<Slot> decisions) {
        return (int) decisions.stream()
                .map(slot -> slot.id().windowKey())
                .filter(key -> key.endsWith(ACTION_WINDOW_SUFFIX))
                .distinct()
                .count();
    }
}
