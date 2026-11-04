package io.github.temporalrift.workbench.search.domain.candidate;

/** The parameter space cannot supply another distinct valid candidate within a bounded number of draws. */
public class CandidateGenerationExhaustedException extends RuntimeException {

    public CandidateGenerationExhaustedException(int draws, int generated) {
        super("no new valid candidate in " + draws + " draws after " + generated + " candidates of the round");
    }
}
