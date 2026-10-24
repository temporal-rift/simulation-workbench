package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.List;
import java.util.Optional;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.MySubmission;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionWindow;
import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/** Finds the submissions a participant's projection records for a decision window. */
final class Submissions {

    private Submissions() {}

    /** The seat's accepted submission in the window, if the projection records one. */
    static Optional<MySubmission> find(List<MySubmission> submissions, WindowRef window) {
        return submissionWindow(window.kind())
                .flatMap(submitted -> submissions.stream()
                        .filter(entry -> entry.getWindow() == submitted
                                && entry.getEraNumber() == window.era()
                                && (window.round() == null || window.round().equals(entry.getRoundNumber())))
                        .findFirst());
    }

    static boolean exists(List<MySubmission> submissions, WindowRef window) {
        return find(submissions, window).isPresent();
    }

    /** The projection's name for the window kind; terminal readiness is never submitted to the service. */
    private static Optional<SubmissionWindow> submissionWindow(WindowKind kind) {
        return switch (kind) {
            case HAND_SELECTION -> Optional.of(SubmissionWindow.HAND_SELECTION);
            case DECLARATION -> Optional.of(SubmissionWindow.DECLARATION);
            case ACTION_ROUND -> Optional.of(SubmissionWindow.ACTION);
            case PARADOX_RESOLUTION -> Optional.of(SubmissionWindow.PARADOX_RESOLUTION);
            case TERMINAL_READINESS -> Optional.empty();
        };
    }
}
