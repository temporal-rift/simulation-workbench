package io.github.temporalrift.workbench.execution.domain.reproduction;

import java.util.List;
import java.util.Objects;

import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/**
 * The first place a reproduction differed from the saved case. Each variant is one kind of difference and
 * carries what was expected and what actually happened.
 */
public sealed interface Divergence {

    /** Position in the saved case's evidence stream; an ending difference sits after the last step. */
    int step();

    Kind kind();

    /** What differed. */
    enum Kind {
        WINDOW,
        OBSERVATION,
        DECISION,
        MISSING_STEP,
        UNEXPECTED_STEP,
        END_REASON,
        WINNERS,
        FINAL_SCORES,
        ERAS,
        SEMANTIC_DIGEST,
        UNEXPECTED_WINDOW,
        COMMAND_REJECTED
    }

    /** A step's seat, window and decision. */
    record StepSummary(int seatIndex, WindowRef window, Candidate decision) {
        public StepSummary {
            Objects.requireNonNull(window);
            Objects.requireNonNull(decision, "decision");
        }
    }

    /** A step's seat, window and the observation it decided from. */
    record ObservedStep(int seatIndex, WindowRef window, EntitledObservation observation) {
        public ObservedStep {
            Objects.requireNonNull(window);
            Objects.requireNonNull(observation, "observation");
        }
    }

    /** The same step was sent by another seat or in another window. */
    record Window(int step, StepSummary expected, StepSummary actual) implements Divergence {
        @Override
        public Kind kind() {
            return Kind.WINDOW;
        }
    }

    /** The seat decided from another observation. */
    record Observation(int step, ObservedStep expected, ObservedStep actual) implements Divergence {
        @Override
        public Kind kind() {
            return Kind.OBSERVATION;
        }
    }

    /** The seat decided otherwise from the same observation. */
    record Decision(int step, StepSummary expected, StepSummary actual) implements Divergence {
        @Override
        public Kind kind() {
            return Kind.DECISION;
        }
    }

    /** The saved case has a step the reproduction never sent. */
    record MissingStep(int step, StepSummary expected) implements Divergence {
        @Override
        public Kind kind() {
            return Kind.MISSING_STEP;
        }
    }

    /** The reproduction sent a step the saved case does not have. */
    record UnexpectedStep(int step, StepSummary actual) implements Divergence {
        @Override
        public Kind kind() {
            return Kind.UNEXPECTED_STEP;
        }
    }

    /** The game ended for another reason. */
    record EndReason(
            int step,
            io.github.temporalrift.workbench.shared.game.EndReason expected,
            io.github.temporalrift.workbench.shared.game.EndReason actual)
            implements Divergence {
        @Override
        public Kind kind() {
            return Kind.END_REASON;
        }
    }

    /** The game had other winners. */
    record Winners(int step, List<Winner> expected, List<Winner> actual) implements Divergence {
        public Winners {
            expected = List.copyOf(expected);
            actual = List.copyOf(actual);
        }

        @Override
        public Kind kind() {
            return Kind.WINNERS;
        }
    }

    /** The game ended with other final scores. */
    record FinalScores(int step, List<FinalScore> expected, List<FinalScore> actual) implements Divergence {
        public FinalScores {
            expected = List.copyOf(expected);
            actual = List.copyOf(actual);
        }

        @Override
        public Kind kind() {
            return Kind.FINAL_SCORES;
        }
    }

    /** The game lasted another number of eras. */
    record Eras(int step, int expected, int actual) implements Divergence {
        @Override
        public Kind kind() {
            return Kind.ERAS;
        }
    }

    /** The semantic digests of the two games differ although everything compared before them matched. */
    record SemanticDigest(int step, String expected, String actual) implements Divergence {
        public SemanticDigest {
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(actual, "actual");
        }

        @Override
        public Kind kind() {
            return Kind.SEMANTIC_DIGEST;
        }
    }

    /**
     * The transcript retains no decision for a window the game opened.
     *
     * @param window the window, or null when the reproduction could not say which
     */
    record UnexpectedWindow(int step, int seatIndex, WindowRef window) implements Divergence {
        @Override
        public Kind kind() {
            return Kind.UNEXPECTED_WINDOW;
        }
    }

    /** The service refused a decision the saved case had accepted. */
    record CommandRejected(int step, int seatIndex, WindowRef window, Candidate refused, List<String> codes)
            implements Divergence {
        public CommandRejected {
            Objects.requireNonNull(window);
            Objects.requireNonNull(refused, "refused");
            codes = List.copyOf(codes);
        }

        @Override
        public Kind kind() {
            return Kind.COMMAND_REJECTED;
        }
    }
}
