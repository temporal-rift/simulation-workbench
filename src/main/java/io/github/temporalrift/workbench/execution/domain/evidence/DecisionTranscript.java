package io.github.temporalrift.workbench.execution.domain.evidence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/**
 * The accepted decisions of a case, one per seat and decision window, as the pinned transcript artifact
 * stores them: one line per decision, in the ledger's canonical order.
 */
public final class DecisionTranscript {

    private static final String FIELD_SEPARATOR = "\t";
    private static final String LINE_SEPARATOR = "\n";

    private static final int FIELD_COUNT = 5;

    private final Map<Decided, Candidate> decisions;

    private DecisionTranscript(Map<Decided, Candidate> decisions) {
        this.decisions = decisions;
    }

    /** The artifact text of the accepted slots. */
    public static String render(List<Slot> accepted) {
        return String.join(
                LINE_SEPARATOR,
                accepted.stream()
                        .map(slot -> slot.id().seatIndex()
                                + FIELD_SEPARATOR
                                + slot.id().window().kind()
                                + FIELD_SEPARATOR
                                + slot.id().window().era()
                                + FIELD_SEPARATOR
                                + (slot.id().window().round() == null
                                        ? ""
                                        : slot.id().window().round().toString())
                                + FIELD_SEPARATOR
                                + slot.request())
                        .toList());
    }

    /**
     * @throws IllegalArgumentException when a line is not a retained decision
     */
    public static DecisionTranscript parse(String artifact) {
        var decisions = new HashMap<Decided, Candidate>();
        if (!artifact.isEmpty()) {
            for (var line : artifact.split(LINE_SEPARATOR)) {
                var fields = line.split(FIELD_SEPARATOR, FIELD_COUNT);
                if (fields.length != FIELD_COUNT) {
                    throw new IllegalArgumentException("Not a retained decision: " + line);
                }
                try {
                    var window = new WindowRef(
                            WindowKind.valueOf(fields[1]),
                            Integer.parseInt(fields[2]),
                            fields[3].isEmpty() ? null : Integer.valueOf(fields[3]));
                    decisions.put(new Decided(Integer.parseInt(fields[0]), window), CandidateCodec.decode(fields[4]));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Not a retained decision: " + line, e);
                }
            }
        }
        return new DecisionTranscript(decisions);
    }

    public Optional<Candidate> decisionFor(int seatIndex, WindowRef window) {
        return Optional.ofNullable(decisions.get(new Decided(seatIndex, window)));
    }

    public int size() {
        return decisions.size();
    }

    private record Decided(int seatIndex, WindowRef window) {}
}
