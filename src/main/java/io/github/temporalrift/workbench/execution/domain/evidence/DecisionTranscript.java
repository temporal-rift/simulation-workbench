package io.github.temporalrift.workbench.execution.domain.evidence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;

/**
 * The accepted decisions of a case, one per seat and decision window, as the pinned transcript artifact
 * stores them: one line per decision, in the ledger's canonical order.
 */
public final class DecisionTranscript {

    private static final String FIELD_SEPARATOR = "\t";
    private static final String LINE_SEPARATOR = "\n";

    private final Map<String, Candidate> decisions;

    private DecisionTranscript(Map<String, Candidate> decisions) {
        this.decisions = decisions;
    }

    /** The artifact text of the accepted slots. */
    public static String render(List<Slot> accepted) {
        return String.join(
                LINE_SEPARATOR,
                accepted.stream()
                        .map(slot -> slot.id().seatIndex()
                                + FIELD_SEPARATOR
                                + slot.id().windowKey()
                                + FIELD_SEPARATOR
                                + slot.request())
                        .toList());
    }

    /**
     * @throws IllegalArgumentException when a line is not a retained decision
     */
    public static DecisionTranscript parse(String artifact) {
        var decisions = new HashMap<String, Candidate>();
        if (!artifact.isEmpty()) {
            for (var line : artifact.split(LINE_SEPARATOR)) {
                var fields = line.split(FIELD_SEPARATOR, 3);
                if (fields.length != 3) {
                    throw new IllegalArgumentException("Not a retained decision: " + line);
                }
                decisions.put(key(Integer.parseInt(fields[0]), fields[1]), CandidateCodec.decode(fields[2]));
            }
        }
        return new DecisionTranscript(decisions);
    }

    public Optional<Candidate> decisionFor(int seatIndex, String windowKey) {
        return Optional.ofNullable(decisions.get(key(seatIndex, windowKey)));
    }

    public int size() {
        return decisions.size();
    }

    private static String key(int seatIndex, String windowKey) {
        return seatIndex + FIELD_SEPARATOR + windowKey;
    }
}
