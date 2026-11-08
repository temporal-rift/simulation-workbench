package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.List;
import java.util.Objects;

/**
 * The confirmation of a campaign's short list on fresh seeds: one run per short-listed candidate, each over the same
 * number of seeds. Confirmation is only ever accepted free of seed overlap, so overlap is not recorded.
 *
 * @param seedCount the seeds every run plays: the criterion's minimum for a confirmation sample
 * @param runs the short-listed candidates in short-list order, after a run of the baseline on a confirmation recorded
 *     when confirmation still played one
 */
public record Confirmation(int seedCount, List<Run> runs) {

    public Confirmation {
        runs = List.copyOf(runs);
        if (seedCount < 1) {
            throw new IllegalArgumentException("seedCount must be at least 1");
        }
    }

    /** Whether every run has been evaluated. */
    public boolean evaluated() {
        return runs.stream().allMatch(run -> run.evaluation() != null);
    }

    /** Whether {@code other} has the same subjects, experiments and runs in the same order. */
    public boolean sameRunsAs(Confirmation other) {
        return other != null
                && runs.size() == other.runs.size()
                && java.util.stream.IntStream.range(0, runs.size()).allMatch(index -> {
                    var mine = runs.get(index);
                    var theirs = other.runs.get(index);
                    return mine.subject().equals(theirs.subject())
                            && mine.experimentId().equals(theirs.experimentId())
                            && mine.runId().equals(theirs.runId());
                });
    }

    /** The same confirmation with the evaluations of its runs, by subject. */
    public Confirmation evaluatedAs(java.util.Map<String, Evaluation> bySubject) {
        return new Confirmation(
                seedCount,
                runs.stream()
                        .map(run ->
                                new Run(run.subject(), run.experimentId(), run.runId(), bySubject.get(run.subject())))
                        .toList());
    }

    /**
     * @param subject a candidate id, or {@code baseline} on a confirmation recorded when it still played the baseline
     * @param evaluation null until the run has been evaluated
     */
    public record Run(String subject, java.util.UUID experimentId, java.util.UUID runId, Evaluation evaluation) {

        public Run {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(experimentId, "experimentId");
            Objects.requireNonNull(runId, "runId");
        }
    }

    /**
     * What the balance criterion says of a confirmation run.
     *
     * @param verdict {@code REJECTED}, {@code CONFIRMED}, {@code UNCONFIRMED} or {@code INVALID}
     * @param score null when the evaluation is invalid; lower is better
     */
    public record Evaluation(String verdict, Double score) {}
}
