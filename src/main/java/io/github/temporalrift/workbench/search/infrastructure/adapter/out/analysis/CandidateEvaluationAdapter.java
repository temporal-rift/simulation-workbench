package io.github.temporalrift.workbench.search.infrastructure.adapter.out.analysis;

import java.util.HashMap;
import java.util.List;

import io.github.temporalrift.workbench.analysis.CandidateScreening;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;

/** Evaluates candidates through the analysis module. */
public class CandidateEvaluationAdapter implements CandidateEvaluation {

    private final CandidateScreening screening;

    public CandidateEvaluationAdapter(CandidateScreening screening) {
        this.screening = screening;
    }

    @Override
    public Screening screen(int criterionVersion, List<CandidateGames> candidates) {
        var screened = screening.screen(
                criterionVersion,
                candidates.stream()
                        .map(candidate -> new CandidateScreening.Candidate(
                                candidate.candidateId(),
                                candidate.games().stream()
                                        .map(games -> new CandidateScreening.Games(games.runId(), games.variantLabel()))
                                        .toList()))
                        .toList());
        var evaluations = new HashMap<String, Evaluation>();
        screened.evaluations()
                .forEach((id, evaluation) -> evaluations.put(
                        id,
                        new Evaluation(
                                evaluation.verdict().name(), evaluation.score(), evaluation.failingGateLineIds())));
        return new Screening(evaluations, screened.dominators());
    }
}
